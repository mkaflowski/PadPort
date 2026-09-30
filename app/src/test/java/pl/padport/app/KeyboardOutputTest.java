package pl.padport.app;

import android.view.KeyEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class KeyboardOutputTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final class State implements KeyboardMapping.State {
        float[] buttons=new float[17],axes=new float[4];
        public float button(int index){return buttons[index];}
        public float axis(int index){return axes[index];}
    }
    @Test public void defaultsCoverRpgMakerMovementConfirmCancelAndDash(){
        State state=new State();state.buttons[0]=1;state.buttons[2]=1;state.axes[0]=-1;
        assertEquals(Set.of(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_DPAD_LEFT),KeyboardMapping.pressed(state,KeyboardMapping.DEFAULT));
        assertEquals(KeyEvent.KEYCODE_Q,KeyboardMapping.DEFAULT[4]);assertEquals(KeyEvent.KEYCODE_W,KeyboardMapping.DEFAULT[5]);
        assertEquals(KeyEvent.KEYCODE_ESCAPE,KeyboardMapping.DEFAULT[9]);
    }
    @Test public void customizedDirectionsAlsoApplyToTheLeftStick(){
        int[] map=KeyboardMapping.DEFAULT.clone();map[12]=KeyEvent.KEYCODE_W;map[14]=KeyEvent.KEYCODE_A;
        State state=new State();state.axes[0]=-1;state.axes[1]=-1;
        assertEquals(Set.of(KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_W),KeyboardMapping.pressed(state,map));
        state.axes[0]=state.axes[1]=0;state.buttons[12]=1;state.buttons[14]=1;
        assertEquals(Set.of(KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_W),KeyboardMapping.pressed(state,map));
    }
    @Test public void multipleButtonsSharingAKeyDoNotReleaseItEarly(){
        State state=new State();KeyboardTransitions transitions=new KeyboardTransitions();
        state.buttons[1]=1;assertEquals(1,transitions.update(KeyboardMapping.pressed(state,KeyboardMapping.DEFAULT)).size());
        state.buttons[3]=1;assertTrue(transitions.update(KeyboardMapping.pressed(state,KeyboardMapping.DEFAULT)).isEmpty());
        state.buttons[1]=0;assertTrue(transitions.update(KeyboardMapping.pressed(state,KeyboardMapping.DEFAULT)).isEmpty());
        state.buttons[3]=0;List<KeyboardTransitions.Change> changes=transitions.update(KeyboardMapping.pressed(state,KeyboardMapping.DEFAULT));
        assertEquals(1,changes.size());assertFalse(changes.get(0).down());assertEquals(KeyEvent.KEYCODE_ESCAPE,changes.get(0).key());
    }
    @Test public void modifiersArePressedBeforeAndReleasedAfterOtherKeys(){
        KeyboardTransitions transitions=new KeyboardTransitions();
        List<KeyboardTransitions.Change> down=transitions.update(Set.of(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT,down.get(0).key());assertEquals(KeyEvent.KEYCODE_ENTER,down.get(1).key());
        assertTrue((down.get(1).modifiers()&KeyEvent.META_CTRL_ON)!=0);
        List<KeyboardTransitions.Change> up=transitions.update(Set.of());
        assertEquals(KeyEvent.KEYCODE_ENTER,up.get(0).key());assertEquals(KeyEvent.KEYCODE_CTRL_LEFT,up.get(1).key());
        assertEquals(0,up.get(1).modifiers());assertTrue(transitions.held().isEmpty());
    }
    @Test public void repeatedSnapshotsDoNotGenerateRepeatedInitialKeydowns(){
        KeyboardTransitions transitions=new KeyboardTransitions();transitions.update(Set.of(KeyEvent.KEYCODE_ENTER));
        assertTrue(transitions.update(Set.of(KeyEvent.KEYCODE_ENTER)).isEmpty());
        assertEquals(1,transitions.update(Set.of()).size());assertTrue(transitions.update(Set.of()).isEmpty());
    }
    @Test public void keyboardModeAndCustomKeysSurviveReloadAndModeSwitch() throws Exception {
        File file=new File(temp.getRoot(),"input.json");
        ControllerOutputSettings.Config defaults=ControllerOutputSettings.read(file);assertFalse(defaults.keyboard);
        defaults.keyboard=true;defaults.keys[0]=KeyEvent.KEYCODE_Z;defaults.keys[8]=0;
        ControllerOutputSettings.write(file,defaults);
        ControllerOutputSettings.Config restored=ControllerOutputSettings.read(file);
        assertTrue(restored.keyboard);assertEquals(KeyEvent.KEYCODE_Z,restored.keys[0]);assertEquals(0,restored.keys[8]);
        restored.keyboard=false;ControllerOutputSettings.write(file,restored);
        assertFalse(ControllerOutputSettings.read(file).keyboard);assertEquals(KeyEvent.KEYCODE_Z,ControllerOutputSettings.read(file).keys[0]);
        assertFalse(new File(file+".tmp").exists());
    }
    @Test public void invalidConfigurationFallsBackToClassic() throws Exception {
        File file=temp.newFile();Files.write(file.toPath(),"broken JSON".getBytes(StandardCharsets.UTF_8));
        ControllerOutputSettings.Config config=ControllerOutputSettings.read(file);
        assertFalse(config.keyboard);assertArrayEquals(KeyboardMapping.DEFAULT,config.keys);
        assertFalse(KeyboardMapping.allowed(KeyEvent.KEYCODE_BUTTON_A));assertTrue(KeyboardMapping.allowed(KeyEvent.KEYCODE_A));
    }
}
