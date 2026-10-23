package com.consense.service.llm;

import com.consense.common.JsonUtils;
import com.consense.domain.QwenGenerationAction;
import com.consense.repository.QwenGenerationActionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** One durable, bounded ledger per Qwen run/ask. No reservations or unknown sends are refunded. */
@Service
public class QwenGenerationLedger {
    private final QwenGenerationActionRepository actions;
    private final TransactionTemplate tx;
    public QwenGenerationLedger(QwenGenerationActionRepository actions,PlatformTransactionManager manager) {
        this.actions=actions;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public static final class Limits {
        public final int preflights,answers,total;public final long lifetimeMs;
        public Limits(int p,int a,int t,long lifetime) {
            if(p<=0||a<=0||t<=0||p>4096||a>4096||t>4096||t>p+a||lifetime<=0||lifetime>86400000)
                throw new IllegalArgumentException("Qwen generation limits must be finite");
            preflights=p;answers=a;total=t;lifetimeMs=lifetime;
        }
    }
    public static final class Reservation {
        private final String actionId,identitySha256;private final int ordinal;
        private Reservation(String action,String identity,int number){actionId=action;identitySha256=identity;ordinal=number;}
        public String getActionId(){return actionId;} public int getOrdinal(){return ordinal;}
    }
    public static final class ActionBudgetExhaustedException extends IllegalStateException {
        public ActionBudgetExhaustedException(){super("qwen_generation_ceiling_exceeded");}
    }
    public void open(String id,String identity,Limits limits) {
        require(id!=null&&id.matches("[A-Za-z0-9:_-]{1,128}")&&sha(identity),"qwen_action_identity_required");
        tx.executeWithoutResult(status->{
            QwenGenerationAction action=actions.lockAction(id).orElse(null);
            if(action==null) {
                action=new QwenGenerationAction();action.setId(id);action.setIdentitySha256(identity);
                action.setMaxPreflights(limits.preflights);action.setMaxAnswers(limits.answers);action.setMaxTotal(limits.total);
                action.setLifetimeMs(limits.lifetimeMs);action.setDeadlineEpochMs(Math.addExact(System.currentTimeMillis(),limits.lifetimeMs));
                actions.saveAndFlush(action);
            } else require(identity.equals(action.getIdentitySha256())&&action.getMaxPreflights()==limits.preflights
                    &&action.getMaxAnswers()==limits.answers&&action.getMaxTotal()==limits.total&&action.getLifetimeMs()==limits.lifetimeMs,"qwen_action_contract_changed");
            // Reopening keeps the original deadline and all reservations, including ambiguous sends.
            require(System.currentTimeMillis()<action.getDeadlineEpochMs(),"qwen_action_deadline_exceeded");
        });
    }
    public Reservation reserve(String actionId,String identity,String phase,String inputSha,boolean answer) {
        require(phase!=null&&phase.matches("[a-z][a-z0-9-]{0,47}")&&sha(inputSha),"qwen_reservation_binding_required");
        return tx.execute(status->{
            QwenGenerationAction a=owned(actionId,identity);require(System.currentTimeMillis()<a.getDeadlineEpochMs(),"qwen_action_deadline_exceeded");
            ArrayNode rows=rows(a);
            for(JsonNode row:rows)require(!(inputSha.equals(row.path("inputSha256").asText())
                    &&(phase.equals(row.path("phase").asText())||!answer&&"usage_preflight_generation".equals(row.path("kind").asText()))),"qwen_generation_already_reserved_no_replay");
            if(!(a.getTotal()<a.getMaxTotal()&&(answer?a.getAnswers()<a.getMaxAnswers():a.getPreflights()<a.getMaxPreflights())))throw new ActionBudgetExhaustedException();
            int ordinal=a.getTotal()+1;ObjectNode row=rows.addObject();row.put("ordinal",ordinal);row.put("phase",phase);row.put("inputSha256",inputSha);
            row.put("kind",answer?"answer_generation":"usage_preflight_generation");row.put("state","RESERVED");row.put("reservedEpochMs",System.currentTimeMillis());
            a.setTotal(ordinal);if(answer)a.setAnswers(a.getAnswers()+1);else a.setPreflights(a.getPreflights()+1);
            a.setReservationsJson(JsonUtils.write(rows));actions.saveAndFlush(a);return new Reservation(actionId,identity,ordinal);
        });
    }
    public void dispatch(Reservation reservation){transition(reservation,"DISPATCHING",null);}
    public void complete(Reservation reservation,Map<String,Object> safeReceipt){transition(reservation,"COMPLETED",safeReceipt);}
    public void unknown(Reservation reservation,String safeFailureKind){
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("failureKind",safeFailureKind!=null&&safeFailureKind.matches("[A-Za-z0-9_]{1,80}")?safeFailureKind:"UnknownFailure");
        transition(reservation,"DISPATCH_UNKNOWN",receipt);
    }
    public void unknown(Reservation reservation,Map<String,Object> safeReceipt){transition(reservation,"DISPATCH_UNKNOWN",safeReceipt);}
    private void transition(Reservation reservation,String state,Map<String,Object> receipt) {
        tx.executeWithoutResult(status->{
            QwenGenerationAction a=owned(reservation.actionId,reservation.identitySha256);ArrayNode rows=rows(a);
            require(reservation.ordinal>0&&reservation.ordinal<=rows.size(),"qwen_reservation_missing");
            ObjectNode row=(ObjectNode)rows.get(reservation.ordinal-1);String prior=row.path("state").asText();
            require("RESERVED".equals(prior)||"DISPATCHING".equals(prior),"qwen_reservation_already_terminal");
            if("DISPATCHING".equals(state)){require("RESERVED".equals(prior),"qwen_reservation_already_dispatched");require(System.currentTimeMillis()<a.getDeadlineEpochMs(),"qwen_action_deadline_exceeded");}
            if("COMPLETED".equals(state))require("DISPATCHING".equals(prior),"qwen_reservation_not_dispatched");
            row.put("state",state);row.put("updatedEpochMs",System.currentTimeMillis());
            if(receipt!=null){ObjectNode safe=row.putObject("receipt");for(Map.Entry<String,Object> e:receipt.entrySet()) {
                String key=e.getKey();Object v=e.getValue();
                if(Arrays.asList("requestSha256","responseSha256","stableObservationSha256").contains(key)&&v instanceof String&&sha((String)v))safe.put(key,(String)v);
                else if(Arrays.asList("promptTokens","completionTokens","totalTokens").contains(key)&&v instanceof Number&&((Number)v).longValue()>=0)safe.put(key,((Number)v).longValue());
                else if("failureKind".equals(key)&&v instanceof String&&((String)v).matches("[A-Za-z0-9_]{1,80}"))safe.put(key,(String)v);
                else if("finishReason".equals(key)&&Arrays.asList("stop","length").contains(v))safe.put(key,(String)v);
            }}
            a.setReservationsJson(JsonUtils.write(rows));actions.saveAndFlush(a);
        });
    }
    private QwenGenerationAction owned(String id,String identity) {
        QwenGenerationAction a=actions.lockAction(id).orElseThrow(()->new IllegalStateException("qwen_action_not_open"));
        require(Objects.equals(identity,a.getIdentitySha256()),"qwen_action_identity_changed");return a;
    }
    private ArrayNode rows(QwenGenerationAction a) {
        JsonNode n=JsonUtils.parse(a.getReservationsJson());require(n.isArray()&&n.size()==a.getTotal()&&a.getTotal()==a.getPreflights()+a.getAnswers()&&a.getTotal()<=4096,"qwen_ledger_inconsistent");return (ArrayNode)n;
    }
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static void require(boolean valid,String reason){if(!valid)throw new IllegalStateException(reason);}
}
