package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import java.util.*;

/** A packet's short references resolve only to the exact sources submitted in that packet. */
final class VettingPacketReferences {
    private final Map<String,String> wireToSource=new LinkedHashMap<>(),sourceToWire=new LinkedHashMap<>();
    VettingPacketReferences(List<Chunk> selected) {
        for(Chunk chunk:selected){
            if(chunk==null||chunk.getId()==null||sourceToWire.containsKey(chunk.getId()))throw new IllegalArgumentException("Duplicate or missing packet source");
            String ref="R"+(wireToSource.size()+1);wireToSource.put(ref,chunk.getId());sourceToWire.put(chunk.getId(),ref);
        }
    }
    Set<String> wireIds(){return Collections.unmodifiableSet(wireToSource.keySet());}
    Map<String,String> bindings(){return Collections.unmodifiableMap(wireToSource);}
    Map<String,String> aliases(){return Collections.unmodifiableMap(sourceToWire);}
    void resolve(List<Record> records){
        for(Record record:records==null?Collections.<Record>emptyList():records)
            if(record!=null)for(Quote quote:record.getEvidence()==null?Collections.<Quote>emptyList():record.getEvidence())if(quote!=null){
                String source=wireToSource.get(quote.getChunkId());
                if(source==null)throw new IllegalArgumentException("Quote reference was not submitted in this packet");
                quote.setChunkId(source);
            }
    }
}
