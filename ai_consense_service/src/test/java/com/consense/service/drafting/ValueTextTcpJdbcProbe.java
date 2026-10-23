package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Observation only: no values, credentials, source documents, or SQL parameters recorded. */
@TestConfiguration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="consense.probe.tcp.enabled", havingValue="true")
public class ValueTextTcpJdbcProbe {
    static final ThreadLocal<Capture> CURRENT=new ThreadLocal<>();
    static class Capture {
        final long started=System.nanoTime();
        final Map<String,long[]> calls=new LinkedHashMap<>();
        final Map<String,Integer> sql=new LinkedHashMap<>();
        void call(String key,long nanos) { long[] row=calls.computeIfAbsent(key,k->new long[2]);row[0]++;row[1]+=nanos; }
    }
    public static class Sql implements StatementInspector {
        public String inspect(String sql) { Capture c=CURRENT.get();if(c!=null)c.sql.put(sql,c.sql.getOrDefault(sql,0)+1);return sql; }
    }
    static void begin(){CURRENT.set(new Capture());}
    static void finish(String phase,Statistics s){
        Capture c=CURRENT.get();CURRENT.remove();Map<String,Object> out=new LinkedHashMap<>();
        out.put("phase",phase);out.put("wallNanos",System.nanoTime()-c.started);
        out.put("callsCountAndNanos",c.calls);out.put("sqlInspectionCounts",c.sql);
        Map<String,Long> h=new LinkedHashMap<>();
        h.put("hqlQueries",s.getQueryExecutionCount());h.put("preparedStatements",s.getPrepareStatementCount());
        h.put("transactions",s.getTransactionCount());h.put("successfulTransactions",s.getSuccessfulTransactionCount());
        h.put("sessionOpen",s.getSessionOpenCount());h.put("sessionClose",s.getSessionCloseCount());
        h.put("connect",s.getConnectCount());h.put("flush",s.getFlushCount());
        h.put("entityInsert",s.getEntityInsertCount());h.put("entityUpdate",s.getEntityUpdateCount());h.put("entityLoad",s.getEntityLoadCount());
        out.put("hibernate",h);System.out.println("INPUT_INITIALIZATION_SQL_OBSERVATION "+JsonUtils.write(out));
    }
    static Object proxy(Object target,Class<?> api,String prefix){
        return Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,args)->{
            long started=System.nanoTime();Object value;
            try{value=m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
            finally{Capture c=CURRENT.get();if(c!=null)c.call(prefix+"."+m.getName(),System.nanoTime()-started);Capture binding=CURRENT.get();if(binding!=null&&prefix.equals("prepared")&&(m.getName().equals("setString")||m.getName().equals("setCharacterStream"))&&args!=null&&args.length>=2){long length=m.getName().equals("setString")?(args[1]==null?-1:((String)args[1]).length()):(args.length>=3?((Number)args[2]).longValue():-1);binding.call("binding."+m.getName()+".parameter"+args[0]+".length"+length,0);}}
            if(value instanceof PreparedStatement)return proxy(value,PreparedStatement.class,"prepared");
            if(value instanceof Statement)return proxy(value,Statement.class,"statement");
            if(value instanceof ResultSet)return proxy(value,ResultSet.class,"result");
            return value;
        });
    }
    @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor ownedTcpLifetime(){
        return factory->{
            org.springframework.beans.factory.support.DefaultListableBeanFactory registry=(org.springframework.beans.factory.support.DefaultListableBeanFactory)factory;
            registry.registerSingleton("ownedValueTextTcpServer",DraftingValueTextTcpBindingTest.server);
            registry.registerDependentBean("ownedValueTextTcpServer","dataSource");
            registry.registerDependentBean("dataSource","entityManagerFactory");
            registry.registerDisposableBean("ownedValueTextTcpServer",()->{
                org.h2.tools.Server server=DraftingValueTextTcpBindingTest.server;server.stop();
                java.nio.file.Path output=java.nio.file.Paths.get(System.getProperty("consense.probe.output"),"owned-server-cleanup.json");
                java.nio.file.Files.write(output,JsonUtils.write(java.util.Arrays.asList(server.getPort(),!server.isRunning(false))).getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE_NEW);
                System.out.println("OWNED_VALUE_TEXT_TCP_SERVER_STOPPED "+server.getPort());
            });
        };
    }
    @Bean static BeanPostProcessor observeDataSource(){
        return new BeanPostProcessor(){
            public Object postProcessAfterInitialization(Object bean,String name){
                if(!(bean instanceof DataSource))return bean;
                return new DelegatingDataSource((DataSource)bean){
                    public Connection getConnection()throws SQLException{return (Connection)proxy(super.getConnection(),Connection.class,"connection");}
                    public Connection getConnection(String user,String password)throws SQLException{return (Connection)proxy(super.getConnection(user,password),Connection.class,"connection");}
                };
            }
        };
    }
}
