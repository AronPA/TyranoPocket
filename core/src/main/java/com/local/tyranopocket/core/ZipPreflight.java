package com.local.tyranopocket.core;

import java.io.*;

/** Bounded ZIP/ZIP64 metadata scan BEFORE ZipFile allocates its central directory.
 * Based on PKWARE APPNOTE 6.3.10 sections 4.3.12 and 4.3.14-16.
 * Encryption, split archives and filesystem links are deliberately unsupported. */
public final class ZipPreflight {
    public static final long MAX_CENTRAL_BYTES=32L*1024*1024;
    private static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static long u32(byte[] b,int p){return Integer.toUnsignedLong((b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24));}
    private static long u64(byte[] b,int p)throws IOException{
        long low=u32(b,p),high=u32(b,p+4);if(high>0x7fffffffL)throw new IOException("ZIP64_INTEGER_LIMIT");return (high<<32)|low;
    }
    private static byte[] read(RandomAccessFile in,long position,int size)throws IOException{
        if(position<0||position>in.length()-size)throw new IOException("ZIP_TRUNCATED");byte[] b=new byte[size];in.seek(position);in.readFully(b);return b;
    }
    public static void inspect(File file,SafeFiles.Monitor monitor)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            int tailSize=(int)Math.min(in.length(),65535+22);
            if(tailSize<22)throw new IOException("ZIP_INVALID");
            byte[] tail=read(in,in.length()-tailSize,tailSize);int found=-1;
            for(int i=tailSize-22;i>=0;i--)if(u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==tailSize){found=i;break;}
            if(found<0)throw new IOException("ZIP_END_RECORD_MISSING");
            long endPosition=in.length()-tailSize+found;
            if(u16(tail,found+4)!=0||u16(tail,found+6)!=0)throw new IOException("ZIP_MULTIDISK_UNSUPPORTED");
            long count=u16(tail,found+10),diskCount=u16(tail,found+8),size=u32(tail,found+12),offset=u32(tail,found+16),centralLimit=endPosition;
            boolean required=count==65535||diskCount==65535||size==0xffffffffL||offset==0xffffffffL;
            byte[] locator=endPosition>=20?read(in,endPosition-20,20):new byte[20];
            boolean zip64=u32(locator,0)==0x07064b50L;
            if(required&&!zip64)throw new IOException("ZIP64_LOCATOR_MISSING");
            if(zip64){
                long normalCount=count,normalDiskCount=diskCount,normalSize=size,normalOffset=offset;
                if(u32(locator,4)!=0||u32(locator,16)!=1)throw new IOException("ZIP_MULTIDISK_UNSUPPORTED");
                long record=u64(locator,8);byte[] header=read(in,record,56);
                if(u32(header,0)!=0x06064b50L)throw new IOException("ZIP64_INVALID");
                long recordSize=u64(header,4);
                if(recordSize<44||recordSize>1024*1024||record!=endPosition-20-12-recordSize)throw new IOException("ZIP64_INVALID");
                if(u32(header,16)!=0||u32(header,20)!=0)throw new IOException("ZIP_MULTIDISK_UNSUPPORTED");
                count=u64(header,32);diskCount=u64(header,24);size=u64(header,40);offset=u64(header,48);centralLimit=record;
                if((normalCount!=65535&&normalCount!=count)||(normalDiskCount!=65535&&normalDiskCount!=diskCount)||(normalSize!=0xffffffffL&&normalSize!=size)||(normalOffset!=0xffffffffL&&normalOffset!=offset))throw new IOException("ZIP64_CONFLICTING_METADATA");
            }
            if(count!=diskCount)throw new IOException("ZIP_COUNT_MISMATCH");
            if(count>SafeFiles.MAX_FILES||size>MAX_CENTRAL_BYTES)throw new IOException("ZIP_INDEX_LIMIT");
            if(offset>centralLimit||size>centralLimit-offset)throw new IOException("ZIP_CENTRAL_BOUNDS");
            long pos=offset,end=offset+size,actual=0;
            while(pos<end){
                if(end-pos<6)throw new IOException("ZIP_CENTRAL_TRUNCATED");
                byte[] prefix=read(in,pos,6);
                if(u32(prefix,0)==0x05054b50L&&pos+6+u16(prefix,4)==end){pos=end;break;}
                if(end-pos<46)throw new IOException("ZIP_CENTRAL_TRUNCATED");
                byte[] h=read(in,pos,46);
                if(u32(h,0)!=0x02014b50L)throw new IOException("ZIP_CENTRAL_INVALID");
                if((u16(h,8)&0x2041)!=0)throw new IOException("ZIP_ENCRYPTION_UNSUPPORTED");
                int method=u16(h,10);if(method!=0&&method!=8)throw new IOException("ZIP_METHOD_UNSUPPORTED");
                if(u16(h,34)!=0)throw new IOException("ZIP_MULTIDISK_UNSUPPORTED");
                int host=h[5]&255,mode=(int)(u32(h,38)>>>16);
                if((host==3||host==19)&&(mode&0170000)==0120000)throw new IOException("ZIP_SYMLINK_UNSUPPORTED");
                long next=pos+46L+u16(h,28)+u16(h,30)+u16(h,32);
                if(next>end)throw new IOException("ZIP_CENTRAL_TRUNCATED");
                pos=next;if(++actual>SafeFiles.MAX_FILES)throw new IOException("ZIP_FILE_COUNT_LIMIT");
                monitor.update("파일 확인 중",actual,count);
            }
            if(actual!=count||pos!=end)throw new IOException("ZIP_COUNT_MISMATCH");
        }
    }
}
