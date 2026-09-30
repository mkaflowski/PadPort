package pl.padport.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class VirtualControllerSettingsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void existingUsersKeepHalfOpacityWithoutASettingsFile(){
        File file=new File(temporary.getRoot(),"opacity");
        assertEquals(50,VirtualControllerSettings.readTransparency(file));
        assertEquals(.5f,VirtualControllerSettings.alphaFor(50),0);
    }
    @Test public void higherTransparencyMakesTheOverlayLessVisible(){
        assertEquals(1f,VirtualControllerSettings.alphaFor(0),0);
        assertEquals(.25f,VirtualControllerSettings.alphaFor(75),0);
        assertEquals(0f,VirtualControllerSettings.alphaFor(100),0);
    }
    @Test public void settingPersistsAndDoesNotLeaveTemporaryFiles() throws Exception {
        File file=new File(temporary.getRoot(),"opacity");
        VirtualControllerSettings.writeTransparency(file,80);
        assertEquals(80,VirtualControllerSettings.readTransparency(new File(file.getPath())));
        VirtualControllerSettings.writeTransparency(file,25);
        assertEquals(25,VirtualControllerSettings.readTransparency(file));
        assertFalse(new File(file+".tmp").exists());
    }
    @Test public void brokenSettingsFallBackAndValuesAreClamped() throws Exception {
        File file=temporary.newFile();Files.write(file.toPath(),"bad value".getBytes(StandardCharsets.UTF_8));
        assertEquals(50,VirtualControllerSettings.readTransparency(file));
        VirtualControllerSettings.writeTransparency(file,200);assertEquals(100,VirtualControllerSettings.readTransparency(file));
        VirtualControllerSettings.writeTransparency(file,-30);assertEquals(0,VirtualControllerSettings.readTransparency(file));
    }
}
