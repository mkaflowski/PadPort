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
}
