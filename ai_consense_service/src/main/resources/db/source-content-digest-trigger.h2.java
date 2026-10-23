org.h2.api.Trigger create() {
    return new org.h2.api.Trigger() {
        final String[] inputs = new String[]{@INPUTS@};
        final String[] outputs = new String[]{@OUTPUTS@};
        final boolean prefixed = @PREFIXED@;
        int[] in, out;
        int aggregate = -1;
        java.lang.reflect.Field lobValue;
        long hashedCharacters;
        public void init(java.sql.Connection c, String schema, String name, String table, boolean before, int type) throws java.sql.SQLException {
            java.util.Map<String,Integer> columns = new java.util.HashMap<>();
            try (java.sql.Statement s=c.createStatement(); java.sql.ResultSet r=s.executeQuery("select * from \""+schema.replace("\"","\"\"")+"\".\""+table.replace("\"","\"\"")+"\" where 1=0")) {
                java.sql.ResultSetMetaData m=r.getMetaData();
                for(int i=1;i<=m.getColumnCount();i++)columns.put(m.getColumnName(i).toLowerCase(java.util.Locale.ROOT),i-1);
            }
            in=new int[inputs.length];out=new int[outputs.length];
            for(int i=0;i<in.length;i++)in[i]=columns.get(inputs[i]);
            for(int i=0;i<out.length;i++)out[i]=columns.get(outputs[i]);
            if(prefixed)aggregate=columns.get("business_content_sha256");
            try{lobValue=org.h2.jdbc.JdbcLob.class.getDeclaredField("value");lobValue.setAccessible(true);}catch(Exception unsupported){throw new java.sql.SQLException("Unsupported H2 immutable LOB identity",unsupported);}
        }
        public void fire(java.sql.Connection c,Object[] oldRow,Object[] newRow) throws java.sql.SQLException {
            boolean prepare=prefixed?"source-content-prepare-v1".equals(newRow[aggregate]):"source-binding-prepare-v1".equals(newRow[out[0]]);
            for(int i=0;i<in.length;i++) {
                Object previous=oldRow==null?null:oldRow[out[i]];
                if(oldRow!=null&&sameStoredValue(oldRow[in[i]],newRow[in[i]])){
                    if(previous instanceof String&&((String)previous).matches("[0-9a-f]{64}"))newRow[out[i]]=previous;
                    else newRow[out[i]]=prepare?digest(newRow[in[i]],prefixed):null;
                }else newRow[out[i]]=digest(newRow[in[i]],prefixed);
            }
            if(aggregate>=0) {
                StringBuilder value=new StringBuilder("source-business-content-v1\n");
                boolean complete=true;for(int i:out){complete&=newRow[i]!=null;value.append(newRow[i]).append('\n');}
                newRow[aggregate]=complete?digest(value.toString(),false):null;
            }
        }
        boolean sameStoredValue(Object a,Object b)throws java.sql.SQLException {
            if(a==b)return true;if(a==null||b==null)return false;
            if(a instanceof String&&b instanceof String)return a.equals(b);
            if(a instanceof org.h2.jdbc.JdbcLob&&b instanceof org.h2.jdbc.JdbcLob)try{
                Object va=lobValue.get(a),vb=lobValue.get(b);if(va==vb)return true;
                if(va instanceof org.h2.value.ValueLob&&vb instanceof org.h2.value.ValueLob){
                    org.h2.value.lob.LobData la=((org.h2.value.ValueLob)va).getLobData(),lb=((org.h2.value.ValueLob)vb).getLobData();if(la==lb)return true;
                    if(la instanceof org.h2.value.lob.LobDataDatabase&&lb instanceof org.h2.value.lob.LobDataDatabase){
                        org.h2.value.lob.LobDataDatabase da=(org.h2.value.lob.LobDataDatabase)la,db=(org.h2.value.lob.LobDataDatabase)lb;
                        return da.getDataHandler()==db.getDataHandler()&&da.getTableId()==db.getTableId()&&da.getLobId()==db.getLobId();
                    }
                }
            }catch(Exception unsupported){throw new java.sql.SQLException("Unable to compare immutable H2 LOB identity",unsupported);}return false;
        }
        String digest(Object value,boolean prefix) throws java.sql.SQLException {
            if(value==null&&!prefix)return null;
            try {
                java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
                java.io.OutputStream sink=new java.io.OutputStream(){public void write(int b){} public void write(byte[] b,int o,int n){}};
                try(java.io.Writer writer=new java.io.OutputStreamWriter(new java.security.DigestOutputStream(sink,md),java.nio.charset.StandardCharsets.UTF_8)) {
                    if(prefix)writer.write(value==null?"source-null-v1":"source-string-v1\n");
                    if(value!=null)try(java.io.Reader reader=value instanceof java.sql.Clob?((java.sql.Clob)value).getCharacterStream():new java.io.StringReader(value.toString())) {
                        char[] buffer=new char[8192];int count;while((count=reader.read(buffer))!=-1){if(value instanceof java.sql.Clob)hashedCharacters+=count;writer.write(buffer,0,count);}
                    }
                }
                byte[] bytes=md.digest();char[] hex=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
                for(int i=0;i<bytes.length;i++){hex[i*2]=digits[(bytes[i]&255)>>>4];hex[i*2+1]=digits[bytes[i]&15];}
                return new String(hex);
            } catch(Exception e){throw new java.sql.SQLException("Unable to maintain source content fingerprint",e);}
        }
        public void close(){} public void remove(){}
    };
}
