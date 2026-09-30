package pl.padport.app;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RpgImageTest {
    private static final byte[] HEADER={0x52,0x50,0x47,0x4d,0x56,0,0,0,0,3,1,0,0,0,0,0};
    @Test public void ordinaryImageNeedsNoKeyAndIsUnchanged() throws Exception {
        byte[] plain={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        assertArrayEquals(plain,RpgImage.decode(plain,null));
    }
    @Test public void mvAndMzHeaderDecodeOnlyFirstSixteenBodyBytes() throws Exception {
        String key="00112233445566778899aabbccddeeff";
        byte[] payload=new byte[128];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*3+7);
        byte[] encrypted=new byte[payload.length+16];System.arraycopy(HEADER,0,encrypted,0,16);
        System.arraycopy(payload,0,encrypted,16,payload.length);
        for(int i=0;i<16;i++)encrypted[16+i]^=(byte)(i*17);
        byte[] original=encrypted.clone();
        assertArrayEquals(payload,RpgImage.decode(encrypted,key));
        assertArrayEquals(original,encrypted); // The original asset buffer is never patched.
        assertArrayEquals(payload,RpgImage.decode(encrypted,key.toUpperCase(java.util.Locale.ROOT)));
    }
    @Test(expected=IOException.class) public void encryptedImageWithoutKeyIsRejected() throws Exception {
        RpgImage.decode(Arrays.copyOf(HEADER,40),"");
    }
    @Test(expected=IOException.class) public void truncatedEncryptedImageIsRejected() throws Exception {
        RpgImage.decode(HEADER,"00112233445566778899aabbccddeeff");
    }
}
