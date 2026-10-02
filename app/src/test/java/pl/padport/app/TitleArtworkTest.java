package pl.padport.app;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.Test;

public class TitleArtworkTest {
    private static ArtworkStore.TitleCandidate c(String path,int w,int h){return new ArtworkStore.TitleCandidate(path,w,h);}

    @Test public void gameSizedArtworkBeatsPluginDemoLayersAndParticles(){
        // Welcome to Elderfield: plugin-drawn title, 1248x720 screen, MOG demo layers left in titles1/.
        List<String> order=ArtworkStore.rankTitles(List.of(
            c("img/titles1/Layer1.png_",816,816),c("img/titles1/Layer2.png_",816,624),c("img/titles1/Particles.png_",31,31),
            c("img/titles1/SIGN CROPPED.png_",1248,720),c("img/titles1/undefined.png_",16,16)),1248,720);
        assertEquals("img/titles1/SIGN CROPPED.png_",order.get(0));
        assertEquals(List.of("img/titles1/Particles.png_","img/titles1/undefined.png_"),order.subList(3,5));
    }
    @Test public void sameAspectRatioThenLargerAreaThenName(){
        List<String> order=ArtworkStore.rankTitles(List.of(
            c("b.png",816,816),c("a.jpg",0,0),c("wide.png",1632,1248),c("small.png",816,624),c("big.png",2000,1000)),816,624);
        assertEquals(List.of("small.png","wide.png","big.png","b.png","a.jpg"),order);
    }
    @Test public void pngSizeReadsEncryptedAndPlainHeaders(){
        byte[] png=new byte[48];
        byte[] start={(byte)0x89,'P','N','G',0x0d,0x0a,0x1a,0x0a,0,0,0,0x0d,'I','H','D','R',0,0,0x04,(byte)0xe0,0,0,0x02,(byte)0xd0};
        System.arraycopy(start,0,png,0,start.length);
        assertArrayEquals(new int[]{1248,720},RpgImage.pngSize(png,null));
        String key="157544fbf5eeff4bbf024d09a42dfa04";
        byte[] encrypted=new byte[64];
        System.arraycopy(new byte[]{0x52,0x50,0x47,0x4d,0x56,0,0,0,0,3,1,0,0,0,0,0},0,encrypted,0,16);
        System.arraycopy(png,0,encrypted,16,48);
        for(int i=0;i<16;i++)encrypted[16+i]^=(byte)Integer.parseInt(key.substring(i*2,i*2+2),16);
        assertArrayEquals(new int[]{1248,720},RpgImage.pngSize(encrypted,key));
        assertNull(RpgImage.pngSize(new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,0},null));
        assertNull(RpgImage.pngSize(encrypted,null));
    }
    /** ELDERFIELD_GAME=<folder>: the sign artwork wins over the demo layers in img/titles1/. */
    @Test public void realElderfieldTitleImages() throws Exception {
        String game=System.getenv("ELDERFIELD_GAME");
        assumeTrue(game!=null&&!game.isEmpty());
        Path folder=Path.of(game,"img","titles1");
        List<ArtworkStore.TitleCandidate> candidates=new ArrayList<>();
        try(Stream<Path> files=Files.list(folder)){
            for(Path file:(Iterable<Path>)files::iterator){
                byte[] head=Arrays.copyOf(Files.readAllBytes(file),48);
                int[] size=RpgImage.pngSize(head,"157544fbf5eeff4bbf024d09a42dfa04");
                candidates.add(c("img/titles1/"+file.getFileName(),size==null?0:size[0],size==null?0:size[1]));
            }
        }
        assertEquals("img/titles1/SIGN CROPPED.png_",ArtworkStore.rankTitles(candidates,1248,720).get(0));
    }
}
