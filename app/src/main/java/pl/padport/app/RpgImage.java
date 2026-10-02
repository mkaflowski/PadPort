package pl.padport.app;

import java.io.IOException;
import java.util.Arrays;

/** The same MV/MZ asset header/XOR step used by the game's image loader. */
final class RpgImage {
    private static final byte[] HEADER={0x52,0x50,0x47,0x4d,0x56,0,0,0,0,3,1,0,0,0,0,0};
    static byte[] decode(byte[] data,String key) throws IOException {
        if(data.length<16 || !Arrays.equals(Arrays.copyOf(data,16),HEADER)) return data;
        if(data.length<32) throw new IOException("Truncated RPG Maker image");
        if(key==null || !key.matches("[0-9a-fA-F]{32}")) throw new IOException("Missing image key in System.json");
        byte[] body=Arrays.copyOfRange(data,16,data.length);
        for(int i=0;i<16;i++) body[i]^=(byte)Integer.parseInt(key.substring(i*2,i*2+2),16);
        return body;
    }
    /** {width, height} from the first 40+ bytes of a (possibly encrypted) PNG, or null. */
    static int[] pngSize(byte[] head,String key){
        byte[] png;
        try{png=decode(head,key);}catch(IOException e){return null;}
        if(png.length<24||(png[0]&0xff)!=0x89||png[1]!='P'||png[2]!='N'||png[3]!='G'||png[12]!='I'||png[13]!='H'||png[14]!='D'||png[15]!='R')return null;
        int w=java.nio.ByteBuffer.wrap(png,16,4).getInt(),h=java.nio.ByteBuffer.wrap(png,20,4).getInt();
        return w>0&&h>0?new int[]{w,h}:null;
    }
}
