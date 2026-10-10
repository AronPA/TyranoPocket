package com.local.tyranopocket.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;

public final class SafeFiles {
    public static final int BUFFER = 64*1024, MAX_FILES=200_000;
    public static final long MAX_BYTES=32L*1024*1024*1024, RESERVE=64L*1024*1024;
    public interface Monitor { void update(String phase,long done,long total) throws IOException; }
    public static final Monitor NONE=(p,d,t)->{};
    public static String relative(String name) throws IOException {
        if(name==null||name.isEmpty()||name.length()>4096||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0) throw new IOException("UNSAFE_PATH");
        String[] parts=name.split("/",-1);
        if(parts.length>64) throw new IOException("PATH_DEPTH_LIMIT");
        for(String part:parts) {
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.getBytes(StandardCharsets.UTF_8).length>255) throw new IOException("UNSAFE_PATH");
            for(int i=0;i<part.length();i++) {
                char c=part.charAt(i);
                if(c<32||c==127) throw new IOException("UNSAFE_PATH");
                if(Character.isHighSurrogate(c)) { if(++i>=part.length()||!Character.isLowSurrogate(part.charAt(i))) throw new IOException("UNSAFE_UNICODE"); }
                else if(Character.isLowSurrogate(c)) throw new IOException("UNSAFE_UNICODE");
            }
        }
        return name;
    }
    public static File child(File root,String name) throws IOException {
        File f=new File(root,relative(name));
        Path base=root.getCanonicalFile().toPath(), path=f.getCanonicalFile().toPath();
        if(!path.startsWith(base)||path.equals(base)) throw new IOException("PATH_ESCAPE");
        Path cursor=root.toPath();
        for(String part:name.split("/")) { cursor=cursor.resolve(part); if(Files.isSymbolicLink(cursor)) throw new IOException("SYMLINK_UNSUPPORTED"); }
        return f;
    }
    public static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static String text(File file,int max) throws IOException {
        if(file.length()>max) throw new IOException("TEXT_LIMIT");
        try(InputStream in=new FileInputStream(file)) { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) { if(out.size()+n>max)throw new IOException("TEXT_LIMIT"); out.write(b,0,n); } return utf8(out.toByteArray()); }
    }
    public static void space(File at,long bytes) throws IOException {
        if(bytes<0||bytes>MAX_BYTES||at.getUsableSpace()<bytes+RESERVE) throw new IOException("NOT_ENOUGH_SPACE");
    }
    public static long copy(InputStream in,File target,long expected,Monitor monitor,String phase) throws IOException {
        if(expected>MAX_BYTES) throw new IOException("SIZE_LIMIT");
        Files.createDirectories(target.getParentFile().toPath());
        long total=0,lastCheck=0; byte[] b=new byte[BUFFER];
        try(FileOutputStream out=new FileOutputStream(target)) {
            int n; while((n=in.read(b))!=-1) {
                total+=n;
                if(total>MAX_BYTES||(expected>=0&&total>expected)) throw new IOException("SIZE_MISMATCH");
                if(total-lastCheck>=8L*1024*1024) {space(target.getParentFile(),n);lastCheck=total;}
                monitor.update(phase,total,expected);out.write(b,0,n);
            }
            if(expected>=0&&total!=expected) throw new IOException("TRUNCATED_FILE");
            out.getFD().sync();
        }
        return total;
    }
    public static List<String> list(File root) throws IOException {
        List<String> result=new ArrayList<>();
        if(!root.isDirectory()) return result;
        try(Stream<Path> stream=Files.walk(root.toPath(),65)) {
            Iterator<Path> it=stream.iterator();
            while(it.hasNext()) {
                Path path=it.next(); if(path.equals(root.toPath()))continue;
                String rel=relative(root.toPath().relativize(path).toString().replace(File.separatorChar,'/'));
                if(Files.isSymbolicLink(path)) throw new IOException("SYMLINK_UNSUPPORTED");
                if(Files.isRegularFile(path))result.add(rel);
                if(result.size()>MAX_FILES)throw new IOException("FILE_COUNT_LIMIT");
            }
        }
        Collections.sort(result); return result;
    }
    public static String hash(File file) throws IOException {
        return hash(file,NONE);
    }
    public static String hash(File file,Monitor monitor) throws IOException {
        MessageDigest digest=sha256();
        try(InputStream in=new FileInputStream(file)) {byte[] b=new byte[BUFFER];int n;long done=0;while((n=in.read(b))!=-1){digest.update(b,0,n);done+=n;monitor.update("게임 파일 확인 중",done,file.length());}}
        return hex(digest.digest());
    }
    public static MessageDigest sha256() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    public static void write(File target,String text) throws IOException {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);copy(new ByteArrayInputStream(data),target,data.length,NONE,"metadata");
    }
    public static void delete(File path) throws IOException {
        if(!Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(path.isDirectory()&&!Files.isSymbolicLink(path.toPath())) {
            File[] children=path.listFiles();if(children==null)throw new IOException("DELETE_FAILED");
            for(File f:children)delete(f);
        }
        Files.delete(path.toPath());
    }
}
