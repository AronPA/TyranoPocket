package com.local.tyranopocket.core;
import java.io.IOException;
import java.util.regex.*;
/** Single HTTP byte ranges for seekable audio/video. Multiple ranges deliberately rejected. */
public final class ByteRange {
    public final long start,end,length;public final boolean partial;
    private ByteRange(long start,long end,boolean partial){this.start=start;this.end=end;this.length=Math.max(0,end-start+1);this.partial=partial;}
    public static ByteRange parse(String header,long size)throws IOException{
        if(size<0)throw new IOException("INVALID_FILE_SIZE");
        if(header==null)return new ByteRange(0,size-1,false);
        Matcher m=Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(header.trim());
        if(size==0||!m.matches())throw new IOException("RANGE_NOT_SATISFIABLE");
        try{
            long start,end=size-1;
            if(m.group(1).isEmpty()){
                long suffix=Long.parseLong(m.group(2));if(suffix<=0)throw new NumberFormatException();start=Math.max(0,size-suffix);
            }else{
                start=Long.parseLong(m.group(1));if(!m.group(2).isEmpty())end=Math.min(end,Long.parseLong(m.group(2)));
            }
            if(start<0||start>end)throw new NumberFormatException();return new ByteRange(start,end,true);
        }catch(NumberFormatException e){throw new IOException("RANGE_NOT_SATISFIABLE",e);}
    }
}
