package com.local.tyranopocket.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class GameImporter {
    public interface Source { void copyTo(File destination,SafeFiles.Monitor monitor) throws IOException; }
    private final File files;
    public GameImporter(File files){this.files=files;}
    public File importZip(InputStream input,String title,SafeFiles.Monitor monitor) throws IOException {
        List<String> log=new ArrayList<>();
        return importSource((destination,m)->{
            File zip=new File(destination.getParentFile(),"source.zip");
            SafeFiles.copy(input,zip,-1,m,"선택한 파일 복사 중");
            new ZipImporter().extract(zip,destination,m,log);Files.delete(zip.toPath());
        },title,monitor,log);
    }
    public File importSource(Source source,String title,SafeFiles.Monitor monitor,List<String> log) throws IOException {
        String id=UUID.randomUUID().toString();File stage=new File(files,"imports/"+id),raw=new File(stage,"source"),ready=new File(stage,"game");
        Files.createDirectories(raw.toPath());Files.createDirectories(ready.toPath());
        boolean committed=false;
        try {
            source.copyTo(raw,monitor);
            Map<String,Object> meta=new GamePackageBuilder().prepare(raw,ready,title,monitor,log);meta.put("id",id);
            SafeFiles.write(new File(ready,"metadata/game.json"),Json.encode(meta));
            File target=new File(files,"games/"+id);Files.createDirectories(target.getParentFile().toPath());
            monitor.update("실행 준비 완료",1,1);
            // Same internal filesystem. No fallback to non-atomic copy into the library.
            Files.move(ready.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);committed=true;return target;
        } finally {try {SafeFiles.delete(stage);}catch(IOException cleanup){if(!committed)log.add("Staging cleanup required on next launch");}}
    }
    public void clearInterruptedImports() throws IOException {SafeFiles.delete(new File(files,"imports"));}
}
