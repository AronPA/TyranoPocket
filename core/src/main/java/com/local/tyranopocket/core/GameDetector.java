package com.local.tyranopocket.core;

import java.io.*;
import java.util.*;
import java.util.regex.*;

public final class GameDetector {
    public static final class ArchiveSet {
        public final File primary,backup,gameRoot;
        ArchiveSet(File p,File b,File r){primary=p;backup=b;gameRoot=r;}
    }
    public static final class Detection {
        public String entry,title,confidence="LOW",dataPrefix="data",icon;
        public boolean electron,scenario,plugins,needsShim;
        public final List<String> unsupported=new ArrayList<>(),warnings=new ArrayList<>();
        public Map<String,Object> json() {
            Map<String,Object> m=new LinkedHashMap<>();m.put("entry",entry);m.put("title",title);m.put("tyranoConfidence",confidence);m.put("electron",electron);m.put("scenario",scenario);m.put("plugins",plugins);m.put("shim",needsShim);m.put("unsupported",unsupported);m.put("warnings",warnings);m.put("icon",icon);return m;
        }
    }
    public ArchiveSet archives(File source) throws IOException {
        List<String> all=SafeFiles.list(source);Map<String,File[]> roots=new LinkedHashMap<>();
        for(String p:all) {
            String lower=p.toLowerCase(Locale.ROOT);
            boolean backup=lower.equals("app.asar.bak")||lower.endsWith("/app.asar.bak");
            if(!lower.endsWith("/app.asar")&&!lower.equals("app.asar")&&!backup)continue;
            File f=SafeFiles.child(source,p);File root=f.getParentFile();boolean resources=root.getName().equalsIgnoreCase("resources");if(resources)root=root.getParentFile();
            File[] slots=roots.computeIfAbsent(root.getPath(),k->new File[4]);int slot=(backup?2:0)+(resources?0:1);
            if(slots[slot]!=null)throw new IOException("AMBIGUOUS_ARCHIVE");slots[slot]=f;
        }
        if(roots.isEmpty())throw new IOException("GAME_NOT_FOUND");
        if(roots.size()>1)throw new IOException("MULTIPLE_GAMES");
        Map.Entry<String,File[]> item=roots.entrySet().iterator().next();File[] f=item.getValue();
        return new ArchiveSet(f[0]!=null?f[0]:f[1],f[2]!=null?f[2]:f[3],new File(item.getKey()));
    }
    public Detection detect(File runtime,String fallback) throws IOException {
        VirtualFileSystem vfs=VirtualFileSystem.scan(runtime);Detection d=new Detection();d.title=fallback;
        Map<String,Integer> scores=new HashMap<>();String mainPath=null,main="";
        File pkg=vfs.file("package.json");
        if(pkg!=null) {
            Map<String,Object> p=Json.object(Json.parse(SafeFiles.text(pkg,1024*1024)));
            d.title=Json.string(p.get("productName"),Json.string(p.get("name"),fallback));
            Object meta=p.get("tyranopocket");if(meta instanceof Map)d.title=Json.string(Json.object(meta).get("title"),d.title);
            mainPath=Json.string(p.get("main"),"index.js");if(mainPath.startsWith("./"))mainPath=mainPath.substring(2);
            File script=vfs.file(mainPath);if(script!=null&&script.length()<4*1024*1024)main=SafeFiles.text(script,4*1024*1024);
            if(mainPath.toLowerCase(Locale.ROOT).endsWith(".html")&&script!=null)scores.put(mainPath,150);
        }
        d.electron=main.contains("BrowserWindow")||main.contains("require('electron')")||main.contains("require(\"electron\")");
        Matcher calls=Pattern.compile("(?:loadFile|loadURL)\\s*\\(([\\s\\S]{0,500}?)\\)").matcher(main);
        while(calls.find()) {
            Matcher quoted=Pattern.compile("['\"]([^'\"]+\\.html)(?:[?#][^'\"]*)?['\"]",Pattern.CASE_INSENSITIVE).matcher(calls.group(1));
            while(quoted.find()) {
                String name=quoted.group(1).replaceFirst("^\\./","");
                if(name.contains(":")||name.startsWith("/"))continue;
                String parent=mainPath!=null&&mainPath.contains("/")?mainPath.substring(0,mainPath.lastIndexOf('/')+1):"";
                String candidate=parent+name;String actual=vfs.resolve(candidate);if(actual!=null)scores.put(actual,200);
            }
        }
        boolean engine=false;int scanned=0;long scanBytes=0;
        for(String path:new TreeSet<>(vfs.paths())) {
            String lower=path.toLowerCase(Locale.ROOT);
            if(lower.endsWith(".ks"))d.scenario=true;
            if(lower.contains("data/others/plugin/"))d.plugins=true;
            if(lower.endsWith("tyrano.js")||lower.endsWith("tyrano.base.js")||lower.endsWith("kag.js"))engine=true;
            if(d.icon==null&&(lower.endsWith("/icon.png")||lower.equals("icon.png")||lower.equals("icon.webp")))d.icon=path;
            if(lower.endsWith(".node"))d.warnings.add("Native addon present (not executed): "+path);
            if(!(lower.endsWith(".html")||lower.endsWith(".js")||lower.endsWith(".ks")))continue;
            File file=vfs.file(path);
            if(file.length()>4*1024*1024||++scanned>10_000||scanBytes+file.length()>64L*1024*1024){d.warnings.add("Static scan limit: "+path);continue;}
            scanBytes+=file.length();String text;
            try{text=SafeFiles.text(file,4*1024*1024);}catch(java.nio.charset.CharacterCodingException e){d.warnings.add("Non-UTF8 bytes retained: "+path);continue;}
            if(lower.endsWith(".html")) {
                int score=scores.getOrDefault(path,0)+(lower.endsWith("index.html")?30:0)+(text.toLowerCase(Locale.ROOT).contains("tyrano")?80:0)+(text.contains("data/")?20:0);
                scores.put(path,score);
                if(d.title.equals(fallback)) {Matcher title=Pattern.compile("(?is)<title[^>]*>([^<]{1,160})</title>").matcher(text);if(title.find())d.title=title.group(1).trim();}
            }
            // Do not mistake the Electron main process wrapper for renderer code.
            if(path.equals(mainPath)&&!lower.endsWith(".html"))continue;
            if(Pattern.compile("\\brequire\\s*\\(|\\bprocess\\.|\\b__dirname\\b|\\b__filename\\b").matcher(text).find())d.needsShim=true;
            Matcher require=Pattern.compile("\\brequire\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").matcher(text);
            while(require.find()) {
                String module=require.group(1).replaceFirst("^node:","");
                if(!Arrays.asList("fs","path","electron").contains(module))d.unsupported.add("Module "+module+" in "+path);
            }
            if(Pattern.compile("\\b(child_process|execSync|spawnSync|winreg|ffi|dlopen)\\b|\\.node['\"]").matcher(text).find())d.unsupported.add("Native/OS dependency in "+path);
            if(Pattern.compile("\\b(writeFileSync|writeFile|mkdirSync|unlinkSync|appendFileSync)\\s*\\(").matcher(text).find())d.unsupported.add("Node filesystem writes in "+path);
            if(text.contains("ipcRenderer")||text.contains("@electron/remote")||text.contains("electron.remote"))d.unsupported.add("Electron IPC/remote in "+path);
        }
        if(scores.isEmpty())throw new IOException("HTML_NOT_FOUND");
        List<Map.Entry<String,Integer>> choices=new ArrayList<>(scores.entrySet());choices.sort((a,b)->Integer.compare(b.getValue(),a.getValue()));
        if(choices.size()>1&&choices.get(0).getValue().equals(choices.get(1).getValue()))throw new IOException("HTML_AMBIGUOUS");
        d.entry=choices.get(0).getKey();d.confidence=engine&&d.scenario&&choices.get(0).getValue()>=80?"HIGH":engine||d.scenario?"MEDIUM":"LOW";
        String parent=d.entry.contains("/")?d.entry.substring(0,d.entry.lastIndexOf('/')):"";
        d.dataPrefix=parent.isEmpty()?"data":parent+"/data";
        if(!parent.isEmpty()&&vfs.paths().stream().anyMatch(p->p.startsWith("data/")))d.dataPrefix="data";
        if(d.title.length()>160)d.title=d.title.substring(0,160);
        return d;
    }
}
