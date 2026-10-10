package com.local.tyranopocket.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec. No executable input, duplicate keys, or unbounded recursion. */
public final class Json {
    private final String source;
    private int pos, nodes;
    private Json(String source) { this.source = source; }
    public static Object parse(String source) throws IOException {
        Json p = new Json(source);
        if (source.startsWith("\ufeff")) p.pos++;
        Object result = p.value(0); p.ws();
        if (p.pos != source.length()) throw p.error();
        return result;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException("JSON_OBJECT_REQUIRED");
        return (Map<String,Object>)value;
    }
    public static long integer(Object value) throws IOException {
        try {
            if (!(value instanceof Number) && !(value instanceof String)) throw new ArithmeticException();
            return new BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException e) { throw new IOException("INVALID_INTEGER", e); }
    }
    public static String string(Object value, String fallback) { return value instanceof String ? (String)value : fallback; }
    private IOException error() { return new IOException("INVALID_JSON_AT_" + pos); }
    private void ws() { while (pos < source.length() && " \r\n\t".indexOf(source.charAt(pos)) >= 0) pos++; }
    private boolean take(char c) { ws(); if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; } return false; }
    private Object value(int depth) throws IOException {
        if (depth > 96 || ++nodes > 1_000_000) throw new IOException("JSON_LIMIT");
        ws(); if (pos >= source.length()) throw error();
        char c = source.charAt(pos);
        if (c == '"') return text();
        if (take('{')) {
            Map<String,Object> map = new LinkedHashMap<>();
            if (take('}')) return map;
            do { ws(); String key = text(); if (!take(':') || map.containsKey(key)) throw error(); map.put(key, value(depth+1)); } while (take(','));
            if (!take('}')) throw error(); return map;
        }
        if (take('[')) {
            List<Object> list = new ArrayList<>();
            if (take(']')) return list;
            do { list.add(value(depth+1)); } while (take(','));
            if (!take(']')) throw error(); return list;
        }
        for (String literal : new String[]{"true","false","null"}) {
            if (source.startsWith(literal,pos)) { pos+=literal.length(); return literal.equals("null")?null:literal.equals("true"); }
        }
        int start=pos;
        while (pos<source.length() && "0123456789eE+-.".indexOf(source.charAt(pos))>=0) pos++;
        String n=source.substring(start,pos);
        if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
        try { return new BigDecimal(n); } catch (NumberFormatException e) { throw error(); }
    }
    private String text() throws IOException {
        if (pos>=source.length() || source.charAt(pos++)!='"') throw error();
        StringBuilder out=new StringBuilder();
        while (pos<source.length()) {
            char c=source.charAt(pos++);
            if(c=='"') return out.toString();
            if(c<' ') throw error();
            if(c=='\\') {
                if(pos>=source.length()) throw error(); c=source.charAt(pos++);
                switch(c) {
                    case '"': case '\\': case '/': out.append(c); break;
                    case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break; case 'r': out.append('\r'); break; case 't': out.append('\t'); break;
                    case 'u':
                        if(pos+4>source.length()) throw error();
                        try { out.append((char)Integer.parseInt(source.substring(pos,pos+4),16)); } catch(NumberFormatException e){throw error();}
                        pos+=4; break;
                    default: throw error();
                }
            } else out.append(c);
        }
        throw error();
    }
    public static String encode(Object value) {
        if(value==null) return "null";
        if(value instanceof Boolean || value instanceof Number) return value.toString();
        if(value instanceof Map) {
            List<String> a=new ArrayList<>();
            for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()) a.add(encode(e.getKey().toString())+":"+encode(e.getValue()));
            return "{"+String.join(",",a)+"}";
        }
        if(value instanceof Iterable) {
            List<String> a=new ArrayList<>(); for(Object v:(Iterable<?>)value) a.add(encode(v)); return "["+String.join(",",a)+"]";
        }
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toString().toCharArray()) {
            if(c=='"'||c=='\\') out.append('\\').append(c);
            else if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
