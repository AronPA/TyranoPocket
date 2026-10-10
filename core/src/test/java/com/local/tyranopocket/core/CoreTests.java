package com.local.tyranopocket.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Executable integration/security tests using the exact production Java core. */
public final class CoreTests {
    static File fixtures,work;static int passed;
    interface Test {void run() throws Exception;}
    static void test(String name,Test test)throws Exception{test.run();passed++;System.out.println("PASS "+name);}
    static void check(boolean condition)throws Exception{if(!condition)throw new AssertionError();}
    static void rejects(Test test)throws Exception{try{test.run();}catch(IOException expected){return;}throw new AssertionError("Expected rejection");}
    static File fresh(String n)throws IOException{File f=new File(work,n);SafeFiles.delete(f);Files.createDirectories(f.toPath());return f;}
    static File fixture(String n){return new File(fixtures,n);}
    static File rawAsar(String name,String json,byte[] data)throws IOException{
        File f=new File(work,name);byte[] h=json.getBytes(StandardCharsets.UTF_8);int pad=(4-h.length%4)%4;
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(f))){for(int i:new int[]{4,8+h.length+pad,4+h.length+pad,h.length})out.writeInt(Integer.reverseBytes(i));out.write(h);out.write(new byte[pad]);out.write(data);}return f;
    }
    static File importZip(String n,File store)throws IOException{try(InputStream in=new FileInputStream(fixture(n))){return new GameImporter(store).importZip(in,"test",SafeFiles.NONE);}}
    public static void main(String[] args)throws Exception{
        fixtures=new File(args[0]);work=new File(args[1]);Files.createDirectories(work.toPath());
        test("strict JSON duplicate keys / trailing text / depth",()->{rejects(()->Json.parse("{\"a\":1,\"a\":2}"));rejects(()->Json.parse("{}garbage"));String j="0";for(int i=0;i<100;i++)j="["+j+"]";final String nested=j;rejects(()->Json.parse(nested));});
        test("ASAR header enumeration nested Unicode and raw legacy bytes",()->{
            try(AsarReader r=new AsarReader(fixture("A/Game/resources/app.asar"))){check(r.entries().size()==8);File out=fresh("a");r.extract(out,null,SafeFiles.NONE);check(new File(out,"data/scenario/first.ks").isFile());check(new File(out,"data/image/日本語 (한글)+%.txt").isFile());check(new File(out,"data/image/legacy.txt").length()==10);}
        });
        test("ASAR unpacked companion / missing companion",()->{
            try(AsarReader r=new AsarReader(fixture("B/Game/resources/app.asar"))){r.extract(fresh("b"),fixture("B/Game/resources/app.asar.unpacked"),SafeFiles.NONE);rejects(()->r.extract(fresh("missing"),null,SafeFiles.NONE));}
        });
        test("ASAR invalid and truncated",()->{
            rejects(()->{try(AsarReader r=new AsarReader(fixture("C/Game/resources/app.asar"))){} });
            File f=new File(work,"truncated.asar");Files.copy(fixture("A/Game/resources/app.asar").toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.setLength(r.length()-1);}rejects(()->{try(AsarReader r=new AsarReader(f)){} });
        });
        test("ASAR path traversal / symlink / unsafe component / overflow rejection",()->{
            for(String key:new String[]{"..","/absolute","C:","a/b","a\\\\b"}){File f=rawAsar("bad.asar","{\"files\":{\""+key+"\":{\"size\":0,\"offset\":\"0\"}}}",new byte[0]);rejects(()->{try(AsarReader r=new AsarReader(f)){} });}
            for(String node:new String[]{"{\"link\":\"../../secret\"}","{\"size\":1,\"offset\":\"18446744073709551615\"}","{\"size\":-1,\"offset\":\"0\"}"}){File f=rawAsar("bad.asar","{\"files\":{\"a\":"+node+"}}",new byte[0]);rejects(()->{try(AsarReader r=new AsarReader(f)){} });}
        });
        test("ASAR shared offsets accepted",()->{File f=rawAsar("shared.asar","{\"files\":{\"a\":{\"size\":1,\"offset\":\"0\"},\"b\":{\"size\":1,\"offset\":\"0\"}}}",new byte[]{42});try(AsarReader r=new AsarReader(f)){File o=fresh("shared");r.extract(o,null,SafeFiles.NONE);check(SafeFiles.hash(new File(o,"a")).equals(SafeFiles.hash(new File(o,"b"))));}});
        test("ASAR integrity rejects altered payload",()->{File f=new File(work,"altered.asar");Files.copy(fixture("A/Game/resources/app.asar").toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.seek(r.length()-1);r.write(7);}try(AsarReader r=new AsarReader(f)){rejects(()->r.extract(fresh("altered"),null,SafeFiles.NONE));}});
        test("96 MiB file streaming with 48 MiB heap",()->{long size=96L*1024*1024;File f=rawAsar("large.asar","{\"files\":{\"large.bin\":{\"size\":"+size+",\"offset\":\"0\"}}}",new byte[0]);try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.setLength(r.length()+size);}try(AsarReader r=new AsarReader(f)){File o=fresh("large");r.extract(o,null,SafeFiles.NONE);check(new File(o,"large.bin").length()==size);}SafeFiles.delete(f);SafeFiles.delete(new File(work,"large"));});
        test("ZIP nested root + ASAR + data overlay + atomic metadata + persistence",()->{File store=fresh("library");File g=importZip("D.zip",store);check(new File(g,"metadata/game.json").isFile());check(new File(g,"runtime/data/scenario/first.ks").isFile());check(SafeFiles.text(new File(g,"runtime/data/image/shared.txt"),100).equals("outside"));Map<String,Object> report=Json.object(Json.parse(SafeFiles.text(new File(g,"compat/report.json"),1024*1024)));check(((List<?>)report.get("overlayConflicts")).size()==1);check(Boolean.TRUE.equals(report.get("plugins")));check("HIGH".equals(report.get("tyranoConfidence")));check(new File(store,"games").listFiles().length==1);check(new File(store,"imports").listFiles().length==0);check(new File(g,"metadata/game.json").isFile());});
        test("backup fallback",()->{File g=importZip("C.zip",fresh("fallback"));Map<String,Object> m=Json.object(Json.parse(SafeFiles.text(new File(g,"compat/report.json"),1024*1024)));check("app.asar.bak".equals(m.get("archiveUsed")));});
        test("unpacked ZIP import",()->{File g=importZip("B.zip",fresh("unpacked"));check(new File(g,"runtime/data/image/日本語 (한글)+%.txt").isFile());});
        test("case-insensitive lookup and collision refusal",()->{File g=importZip("E.zip",fresh("case"));VirtualFileSystem v=VirtualFileSystem.scan(new File(g,"runtime"));check(v.file("data/bgimage/character01.png").getName().equals("Character01.png"));rejects(()->v.file("data/image/PAIR.png"));check(v.file("data/image/Pair.png")!=null);check(v.collisions().size()==1);});
        test("URL percent encoding preserves plus Unicode and blocks escapes",()->{check(VirtualFileSystem.urlPath("/data/a+b%25%20%ED%95%9C.txt").equals("data/a+b% 한.txt"));for(String p:new String[]{"/%2e%2e/secret","/%2fetc/passwd","/a/%5csecret","/a/%00x","/a/%zz"})rejects(()->VirtualFileSystem.urlPath(p));});
        test("ZIP traversal absolute Windows duplicate fail without library entry",()->{for(String bad:new String[]{"traversal","absolute","windows","duplicate"}){File store=fresh("zip-"+bad);rejects(()->importZip("bad-"+bad+".zip",store));check(!new File(store,"games").exists());check(new File(store,"imports").listFiles().length==0);}});
        test("cancellation cleans staging",()->{File store=fresh("cancel");try(InputStream in=new FileInputStream(fixture("A.zip"))){rejects(()->new GameImporter(store).importZip(in,"test",(p,d,t)->{throw new InterruptedIOException("CANCELLED");}));}check(!new File(store,"games").exists());check(new File(store,"imports").listFiles().length==0);});
        test("invalid folder rejects without commit",()->{File store=fresh("invalid");rejects(()->new GameImporter(store).importSource((dir,m)->SafeFiles.write(new File(dir,"hello.txt"),"hello"),"bad",SafeFiles.NONE,new ArrayList<>()));check(!new File(store,"games").exists());});
        test("sandbox symlinks rejected",()->{File dir=fresh("links");Files.createSymbolicLink(new File(dir,"escape").toPath(),work.toPath().toAbsolutePath());rejects(()->SafeFiles.child(dir,"escape/private"));rejects(()->SafeFiles.list(dir));});
        test("HTTP media range start suffix clamping and invalid range rejection",()->{
            check(ByteRange.parse("bytes=0-9",100).length==10);check(ByteRange.parse("bytes=90-",100).start==90);
            check(ByteRange.parse("bytes=-10",100).start==90);check(ByteRange.parse("bytes=0-1000",100).end==99);check(ByteRange.parse(null,0).length==0);
            for(String h:new String[]{"bytes=100-","bytes=9-2","bytes=-0","bytes=-","bytes=0-1,3-4","bytes=99999999999999999999-"})rejects(()->ByteRange.parse(h,100));
            rejects(()->ByteRange.parse("bytes=0-",0));
        });
        test("directory import production core and original files unchanged",()->{
            File source=fixture("A/Game");String before=SafeFiles.hash(new File(source,"resources/app.asar"));File store=fresh("folder");
            File g=new GameImporter(store).importSource((destination,monitor)->{for(String p:SafeFiles.list(source)){File f=SafeFiles.child(source,p);try(InputStream in=new FileInputStream(f)){SafeFiles.copy(in,SafeFiles.child(destination,p),f.length(),monitor,"copy");}}},"folder",SafeFiles.NONE,new ArrayList<>());
            check(new File(g,"runtime/index.html").isFile());check(before.equals(SafeFiles.hash(new File(source,"resources/app.asar"))));
            check(!new File(g,"runtime/Game.exe").exists());
        });
        test("primary archive preferred over backup",()->{
            File source=fresh("both");Files.createDirectories(new File(source,"resources").toPath());
            Files.copy(fixture("A/Game/resources/app.asar").toPath(),new File(source,"resources/app.asar").toPath());SafeFiles.write(new File(source,"resources/app.asar.bak"),"broken backup");
            File game=fresh("both-ready");new GamePackageBuilder().prepare(source,game,"both",SafeFiles.NONE,new ArrayList<>());
            Map<String,Object> report=Json.object(Json.parse(SafeFiles.text(new File(game,"compat/report.json"),1024*1024)));check("app.asar".equals(report.get("archiveUsed")));
        });
        test("ZIP64 metadata and file extraction",()->{File o=fresh("zip64");new ZipImporter().extract(fixture("zip64.zip"),o,SafeFiles.NONE,new ArrayList<>());check(SafeFiles.text(new File(o,"long-file.txt"),100).equals("01234567890123456789"));});
        test("legacy Japanese ZIP filename decoding",()->{File o=fresh("sjis");new ZipImporter().extract(fixture("legacy-sjis.zip"),o,SafeFiles.NONE,new ArrayList<>());check(new File(o,"日本語.txt").isFile());});
        test("ZIP CRC corruption and symlink refusal",()->{for(String p:new String[]{"bad-crc.zip","bad-link.zip"})rejects(()->new ZipImporter().extract(fixture(p),fresh("reject-"+p),SafeFiles.NONE,new ArrayList<>()));});
        test("huge ZIP metadata rejected before ZipFile allocation",()->{
            File f=new File(work,"huge-index.zip");Files.copy(fixture("A.zip").toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);
            try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.seek(r.length()-22+12);r.writeInt(Integer.reverseBytes(1024*1024*1024));}
            rejects(()->ZipPreflight.inspect(f,SafeFiles.NONE));
        });
        System.out.println("RESULT "+passed+" tests passed; Android/Gradle/official-ASAR tests NOT RUN");
    }
}
