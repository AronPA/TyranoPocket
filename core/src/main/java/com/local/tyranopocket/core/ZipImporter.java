package com.local.tyranopocket.core;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Random-access central-directory preflight, buffered payload extraction, CRC verification. */
public final class ZipImporter {
    public void extract(File archive,File destination,SafeFiles.Monitor monitor,List<String> diagnostics) throws IOException {
        Files.createDirectories(destination.toPath());
        ZipPreflight.inspect(archive,monitor);
        ZipFile opened;
        try {opened=new ZipFile(archive,StandardCharsets.UTF_8);}
        catch(IllegalArgumentException|ZipException utf8Failure) {
            try {opened=new ZipFile(archive,Charset.forName("MS932"));diagnostics.add("ZIP legacy filename decoding: MS932; original payload bytes unchanged");}
            catch(IllegalArgumentException|ZipException legacyFailure) {throw new IOException("ZIP_INVALID",legacyFailure);}
        }
        try(ZipFile zip=opened) {
            List<? extends ZipEntry> entries=Collections.list(zip.entries());
            if(entries.size()>SafeFiles.MAX_FILES)throw new IOException("ZIP_FILE_COUNT_LIMIT");
            long total=0,done=0;Set<String> seen=new HashSet<>(),files=new HashSet<>();
            for(ZipEntry e:entries) {
                String p=path(e);SafeFiles.child(destination,p);
                if(!seen.add(p))throw new IOException("ZIP_DUPLICATE_PATH");
                if(!e.isDirectory()) {
                    if(e.getSize()<0||e.getSize()>SafeFiles.MAX_BYTES-total)throw new IOException("ZIP_SIZE_LIMIT");
                    if(e.getMethod()!=ZipEntry.DEFLATED&&e.getMethod()!=ZipEntry.STORED)throw new IOException("ZIP_METHOD_UNSUPPORTED");
                    total+=e.getSize();files.add(p);
                }
                monitor.update("파일 확인 중",seen.size(),entries.size());
            }
            for(String p:seen) {int i=p.lastIndexOf('/');while(i>0){if(files.contains(p.substring(0,i)))throw new IOException("ZIP_PATH_CONFLICT");i=p.lastIndexOf('/',i-1);}}
            SafeFiles.space(destination,total);
            for(ZipEntry e:entries) {
                File target=SafeFiles.child(destination,path(e));
                if(e.isDirectory()){Files.createDirectories(target.toPath());continue;}
                long base=done,expectedTotal=total;
                CRC32 crc=new CRC32();
                try(InputStream in=new CheckedInputStream(zip.getInputStream(e),crc)) {
                    done+=SafeFiles.copy(in,target,e.getSize(),(p,d,t)->monitor.update("게임 복사 중",base+d,expectedTotal),"게임 복사 중");
                }
                if(e.getCrc()!=crc.getValue())throw new IOException("ZIP_CRC_MISMATCH");
            }
        } catch(IllegalArgumentException e) {throw new IOException("ZIP_INVALID",e);}
    }
    private String path(ZipEntry e) throws IOException {
        String name=e.getName();if(e.isDirectory()&&name.endsWith("/"))name=name.substring(0,name.length()-1);
        return SafeFiles.relative(name);
    }
}
