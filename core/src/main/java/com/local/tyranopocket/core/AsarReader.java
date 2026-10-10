package com.local.tyranopocket.core;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Electron ASAR: 8-byte size Pickle, then header Pickle, then file data.
 * Format references and explicit unsupported cases: docs/asar-format.md. */
public final class AsarReader implements Closeable {
    public static final int MAX_HEADER=16*1024*1024;
    public static final class Entry {
        public final String path,hash; public final long offset,size; public final boolean unpacked;
        Entry(String p,long o,long s,boolean u,String h){path=p;offset=o;size=s;unpacked=u;hash=h;}
    }
    private final RandomAccessFile input;
    private final long dataOffset;
    private final List<Entry> entries=new ArrayList<>();
    public AsarReader(File archive) throws IOException {
        input=new RandomAccessFile(archive,"r");
        try {
            if(input.length()<16||uint()!=4)throw new IOException("ASAR_INVALID_PICKLE");
            long header=uint();
            if(header<8||header>MAX_HEADER||header%4!=0||header>input.length()-8)throw new IOException("ASAR_HEADER_BOUNDS");
            long payload=uint(),jsonSize=uint();
            if(payload!=header-4||jsonSize<2||jsonSize>header-8||8+((jsonSize+3)&~3L)!=header)throw new IOException("ASAR_HEADER_BOUNDS");
            byte[] json=new byte[(int)jsonSize];input.readFully(json);
            dataOffset=8+header;
            Map<String,Object> root=Json.object(Json.parse(SafeFiles.utf8(json)));
            walk(Json.object(root.get("files")),"",false,0);
            long total=0;
            for(Entry e:entries){ if(e.size>SafeFiles.MAX_BYTES-total)throw new IOException("ASAR_SIZE_LIMIT");total+=e.size; }
        } catch(IOException|RuntimeException e) {input.close();throw e;}
    }
    private long uint() throws IOException {return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));}
    private void walk(Map<String,Object> files,String parent,boolean inherited,int depth) throws IOException {
        if(depth>64)throw new IOException("ASAR_DEPTH_LIMIT");
        for(Map.Entry<String,Object> item:files.entrySet()) {
            if(item.getKey().contains("/"))throw new IOException("ASAR_INVALID_COMPONENT");
            String path=SafeFiles.relative(parent+item.getKey());
            Map<String,Object> node=Json.object(item.getValue());
            if(node.containsKey("link"))throw new IOException("ASAR_SYMLINK_UNSUPPORTED");
            boolean unpacked=inherited||Boolean.TRUE.equals(node.get("unpacked"));
            if(node.containsKey("files")) {
                if(node.containsKey("size")||node.containsKey("offset"))throw new IOException("ASAR_NODE_CONFLICT");
                walk(Json.object(node.get("files")),path+"/",unpacked,depth+1);continue;
            }
            long size=Json.integer(node.get("size")),offset=unpacked?0:Json.integer(node.get("offset"));
            if(size<0||offset<0||size>SafeFiles.MAX_BYTES)throw new IOException("ASAR_SIZE_LIMIT");
            if(!unpacked&&(offset>input.length()-dataOffset||size>input.length()-dataOffset-offset))throw new IOException("ASAR_TRUNCATED");
            String hash=null;
            if(node.containsKey("integrity")) {
                Map<String,Object> integrity=Json.object(node.get("integrity"));
                if(!"SHA256".equals(integrity.get("algorithm")))throw new IOException("ASAR_HASH_ALGORITHM_UNSUPPORTED");
                hash=Json.string(integrity.get("hash"),"");
                if(!hash.matches("(?i)[a-f0-9]{64}"))throw new IOException("ASAR_HASH_INVALID");
            }
            entries.add(new Entry(path,offset,size,unpacked,hash));
            if(entries.size()>SafeFiles.MAX_FILES)throw new IOException("ASAR_FILE_COUNT_LIMIT");
        }
    }
    public List<Entry> entries(){return Collections.unmodifiableList(entries);}
    public void extract(File destination,File unpackedRoot,SafeFiles.Monitor monitor) throws IOException {
        Files.createDirectories(destination.toPath());
        long total=0,done=0;for(Entry e:entries)total+=e.size;
        SafeFiles.space(destination,total);
        byte[] buffer=new byte[SafeFiles.BUFFER];
        for(Entry e:entries) {
            File target=SafeFiles.child(destination,e.path);
            Files.createDirectories(target.getParentFile().toPath());
            MessageDigest digest=SafeFiles.sha256();long remaining=e.size;
            InputStream outside=null;
            if(e.unpacked) {
                if(unpackedRoot==null)throw new IOException("ASAR_UNPACKED_MISSING");
                File f=SafeFiles.child(unpackedRoot,e.path);
                if(!f.isFile()||f.length()!=e.size)throw new IOException("ASAR_UNPACKED_MISSING");
                outside=new FileInputStream(f);
            } else input.seek(dataOffset+e.offset);
            try(InputStream companion=outside;FileOutputStream output=new FileOutputStream(target)) {
                monitor.update("게임 파일 준비 중",done,total);
                while(remaining>0) {
                    int n=companion==null?input.read(buffer,0,(int)Math.min(buffer.length,remaining)):companion.read(buffer,0,(int)Math.min(buffer.length,remaining));
                    if(n<0)throw new IOException("ASAR_TRUNCATED");
                    output.write(buffer,0,n);digest.update(buffer,0,n);remaining-=n;done+=n;
                    monitor.update("게임 파일 준비 중",done,total);
                }
                output.getFD().sync();
            }
            if(e.hash!=null&&!e.hash.equalsIgnoreCase(SafeFiles.hex(digest.digest())))throw new IOException("ASAR_INTEGRITY_MISMATCH");
        }
    }
    @Override public void close() throws IOException{input.close();}
}
