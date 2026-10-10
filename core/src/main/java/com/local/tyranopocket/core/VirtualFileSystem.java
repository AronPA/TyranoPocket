package com.local.tyranopocket.core;

import java.io.*;
import java.net.URLDecoder;
import java.util.*;

/** Exact lookup first; ambiguous case-folded paths never select an arbitrary file. */
public final class VirtualFileSystem {
    private final File root;
    private final Set<String> exact=new HashSet<>(),directories=new HashSet<>();
    private final Map<String,List<String>> folded=new HashMap<>();
    private final Map<String,Integer> foldedDirectories=new HashMap<>();
    public VirtualFileSystem(File root,Collection<String> paths) throws IOException {
        this.root=root;
        for(String p:paths) {
            SafeFiles.relative(p);exact.add(p);
            folded.computeIfAbsent(p.toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(p);
            for(int i=p.lastIndexOf('/');i>0;i=p.lastIndexOf('/',i-1))directories.add(p.substring(0,i));
        }
        for(String d:directories)foldedDirectories.merge(d.toLowerCase(Locale.ROOT),1,Integer::sum);
    }
    public static VirtualFileSystem scan(File root) throws IOException{return new VirtualFileSystem(root,SafeFiles.list(root));}
    public Set<String> paths(){return Collections.unmodifiableSet(exact);}
    public Map<String,List<String>> collisions(){Map<String,List<String>> result=new TreeMap<>();for(Map.Entry<String,List<String>> e:folded.entrySet())if(e.getValue().size()>1)result.put(e.getKey(),e.getValue());return result;}
    public String resolve(String p) throws IOException {
        SafeFiles.relative(p);
        if(exact.contains(p))return p;
        List<String> candidates=folded.get(p.toLowerCase(Locale.ROOT));
        if(candidates==null)return null;
        if(candidates.size()!=1)throw new IOException("AMBIGUOUS_CASE_PATH");
        return candidates.get(0);
    }
    public File file(String p) throws IOException{String actual=resolve(p);return actual==null?null:SafeFiles.child(root,actual);}
    public static String urlPath(String encoded) throws IOException {
        try {
            if(encoded.startsWith("/"))encoded=encoded.substring(1);
            // URLDecoder implements form encoding, so protect literal + before decoding once.
            return SafeFiles.relative(URLDecoder.decode(encoded.replace("+","%2B"),"UTF-8"));
        } catch(IllegalArgumentException e){throw new IOException("URL_ENCODING_INVALID",e);}
    }
    public boolean exists(String p) throws IOException{
        if(p.isEmpty()||directories.contains(p))return true;
        SafeFiles.relative(p);int matches=foldedDirectories.getOrDefault(p.toLowerCase(Locale.ROOT),0);
        if(matches>1)throw new IOException("AMBIGUOUS_CASE_PATH");
        return matches==1||resolve(p)!=null;
    }
    public static String mime(String path) {
        int dot=path.lastIndexOf('.');String ext=dot<0?"":path.substring(dot+1).toLowerCase(Locale.ROOT);
        switch(ext) {
            case "html":case "htm":return "text/html";case "js":case "mjs":return "text/javascript";
            case "css":return "text/css";case "json":return "application/json";case "ks":case "txt":case "csv":return "text/plain";
            case "png":return "image/png";case "jpg":case "jpeg":return "image/jpeg";case "webp":return "image/webp";case "gif":return "image/gif";case "svg":return "image/svg+xml";case "ico":return "image/x-icon";
            case "ogg":case "oga":return "audio/ogg";case "mp3":return "audio/mpeg";case "wav":return "audio/wav";case "m4a":return "audio/mp4";
            case "mp4":return "video/mp4";case "webm":return "video/webm";case "woff":return "font/woff";case "woff2":return "font/woff2";case "ttf":return "font/ttf";
            default:return "application/octet-stream";
        }
    }
}
