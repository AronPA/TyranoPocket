package com.local.tyranopocket.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class GamePackageBuilder {
    public Map<String,Object> prepare(File source,File game,String fallback,SafeFiles.Monitor monitor,List<String> log) throws IOException {
        GameDetector detector=new GameDetector();GameDetector.ArchiveSet archives=detector.archives(source);
        File runtime=new File(game,"runtime");Files.createDirectories(runtime.toPath());
        File archive=archives.primary!=null?archives.primary:archives.backup;
        if(archive==null)throw new IOException("GAME_NOT_FOUND");
        if(archives.primary==null)log.add("Only backup archive present");
        try {extract(archive,runtime,monitor);}
        catch(IOException error) {
            if(error instanceof InterruptedIOException||"NOT_ENOUGH_SPACE".equals(error.getMessage())||archives.backup==null||archive.equals(archives.backup))throw error;
            log.add("Primary archive failed: "+error.getMessage()+"; backup selected");
            monitor.update("기본 게임 파일을 읽을 수 없어 백업 게임 파일로 다시 시도합니다",0,-1);
            SafeFiles.delete(runtime);Files.createDirectories(runtime.toPath());archive=archives.backup;extract(archive,runtime,monitor);
        }
        log.add("ASAR parse and extraction OK");
        GameDetector.Detection detection=detector.detect(runtime,fallback);
        List<Map<String,Object>> conflicts=new ArrayList<>();
        File external=new File(archives.gameRoot,"data");
        if(!external.isDirectory()) {
            File[] siblings=archives.gameRoot.listFiles();if(siblings!=null)for(File f:siblings)if(f.isDirectory()&&f.getName().equalsIgnoreCase("data")){external=f;break;}
        }
        if(external.isDirectory()) {
            VirtualFileSystem existing=VirtualFileSystem.scan(runtime);
            List<String> paths=SafeFiles.list(external);long count=0;
            for(String path:paths) {
                monitor.update("게임 자료 결합 중",count++,paths.size());
                String targetPath=detection.dataPrefix+"/"+path;String old=existing.resolve(targetPath);
                File from=SafeFiles.child(external,path);SafeFiles.space(runtime,from.length());
                if(old!=null) {
                    Map<String,Object> conflict=new LinkedHashMap<>();conflict.put("path",targetPath);conflict.put("internalPath",old);conflict.put("internalSha256",SafeFiles.hash(SafeFiles.child(runtime,old),monitor));conflict.put("externalSha256",SafeFiles.hash(from,monitor));conflict.put("winner","external");conflicts.add(conflict);targetPath=old;
                }
                try(InputStream in=new FileInputStream(from)){SafeFiles.copy(in,SafeFiles.child(runtime,targetPath),from.length(),monitor,"게임 자료 결합 중");}
            }
            log.add("External data overlay OK; conflicts="+conflicts.size());
        }
        detection=detector.detect(runtime,fallback);
        VirtualFileSystem vfs=VirtualFileSystem.scan(runtime);
        Map<String,Object> report=detection.json();report.put("externalOverlay",external.isDirectory());report.put("overlayConflicts",conflicts);report.put("caseCollisions",vfs.collisions());report.put("archiveUsed",archive.getName());
        log.add("HTML entry found: "+detection.entry);log.add("Tyrano confidence: "+detection.confidence);log.add("Scenario: "+detection.scenario+"; plugin directory: "+detection.plugins);
        report.put("importLog",log);
        List<String> paths=new ArrayList<>(vfs.paths());Collections.sort(paths);
        SafeFiles.write(new File(game,"metadata/paths.json"),Json.encode(paths));
        SafeFiles.write(new File(game,"compat/report.json"),Json.encode(report));
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("title",detection.title);metadata.put("entry",detection.entry);metadata.put("shim",detection.needsShim);metadata.put("icon",detection.icon);metadata.put("orientation","auto");metadata.put("created",System.currentTimeMillis());metadata.put("supported",detection.unsupported.isEmpty());
        Files.createDirectories(new File(game,"saves").toPath());return metadata;
    }
    private void extract(File archive,File runtime,SafeFiles.Monitor monitor) throws IOException {
        File unpacked=new File(archive.getPath()+".unpacked");
        if(!unpacked.isDirectory()&&archive.getName().endsWith(".bak"))unpacked=new File(archive.getPath().substring(0,archive.getPath().length()-4)+".unpacked");
        try(AsarReader reader=new AsarReader(archive)){reader.extract(runtime,unpacked.isDirectory()?unpacked:null,monitor);}
    }
}
