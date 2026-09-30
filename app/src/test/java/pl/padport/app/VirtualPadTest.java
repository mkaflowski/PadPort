package pl.padport.app;

import android.view.KeyEvent;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class VirtualPadTest {
    private static VirtualPadState active(){VirtualPadState state=new VirtualPadState();state.enabled(true);return state;}

    @Test public void multitouchCanMoveAndConfirmAtTheSameTime(){
        VirtualPadState state=active();state.touch(4,1<<14);state.touch(12,1);
        assertEquals(1,state.button(14),0);assertEquals(1,state.button(0),0);assertEquals(-1,state.axis(0),0);
        state.release(12);assertEquals(1,state.button(14),0);assertEquals(0,state.button(0),0);
        state.release(4);assertEquals(0,state.buttons());
    }
    @Test public void releasingOneFingerDoesNotReleaseAnotherOnTheSameButton(){
        VirtualPadState state=active();state.touch(1,1);state.touch(7,1);state.release(1);
        assertEquals(1,state.button(0),0);state.release(7);assertEquals(0,state.button(0),0);
    }
    @Test public void slidingOutsideControlsReleasesButKeepsThePointerTracked(){
        VirtualPadState state=active();state.touch(2,1);state.touch(2,0);
        assertEquals(0,state.buttons());assertTrue(state.tracks(2));
        state.touch(2,1<<1);assertEquals(1,state.button(1),0);
    }
    @Test public void pauseAndDisableClearHeldButtonsAndIgnoreNewTouches(){
        VirtualPadState state=active();state.touch(0,1);state.paused(true);state.touch(1,1<<14);
        assertEquals(0,state.buttons());assertFalse(state.tracks(0));
        state.paused(false);assertEquals(0,state.buttons());state.touch(2,1);state.enabled(false);state.touch(3,1);
        assertEquals(0,state.buttons());state.enabled(true);assertEquals(0,state.buttons());
    }
    @Test public void touchReleaseDoesNotCancelThePhysicalController(){
        VirtualPadState state=active();state.touch(0,1);assertEquals(1,state.mergeButton(0,1),0);
        state.release(0);assertEquals(1,state.mergeButton(0,1),0);
        state.touch(1,1<<1);assertEquals(1,state.mergeButton(1,0),0);
        assertEquals(.7f,state.mergeButton(6,.7f),0); // Physical analog trigger stays analog.
        assertEquals(.4f,state.mergeAxis(2,.4f),0);
    }
    @Test public void dpadSupportsDiagonalsAndADeadCenter(){
        VirtualPadLayout layout=new VirtualPadLayout(1280,800,1.5f);
        assertTrue(layout.contains(layout.dpadX,layout.dpadY));
        assertEquals(0,layout.maskAt(layout.dpadX,layout.dpadY));
        int diagonal=layout.maskAt(layout.dpadX-40*layout.unit,layout.dpadY-40*layout.unit);
        assertEquals((1<<12)|(1<<14),diagonal);
        VirtualPadState state=active();state.touch(1,diagonal);
        assertEquals(-1,state.axis(0),0);assertEquals(-1,state.axis(1),0);
        assertFalse(layout.contains(640,300)); // The middle of the game stays touchable.
    }
    @Test public void layoutsFitDifferentScreensAndButtonsHaveDistinctHitAreas(){
        for(int[] size:new int[][]{{1280,800},{1920,1080},{480,800},{640,360}}){
            VirtualPadLayout layout=new VirtualPadLayout(size[0],size[1],1.5f);
            for(VirtualPadLayout.Button button:layout.buttons){
                assertTrue(button.left()>=0&&button.top()>=0&&button.right()<=size[0]&&button.bottom()<=size[1]);
                assertEquals(1<<button.index(),layout.maskAt(button.centerX(),button.centerY()));
                assertFalse(layout.inDpad(button.centerX(),button.centerY()));
            }
        }
    }
    @Test public void rgssUsesTheSameTouchButtonState(){
        VirtualPadState state=active();state.touch(0,(1<<12)|(1<<15));state.touch(1,1);
        Set<Integer> keys=RgssKeys.pressed(new RgssKeys.State(){
            public float button(int index){return state.mergeButton(index,0);}
            public float axis(int index){return state.mergeAxis(index,0);}
        });
        assertTrue(keys.contains(KeyEvent.KEYCODE_ENTER));
        assertTrue(keys.contains(KeyEvent.KEYCODE_DPAD_UP));assertTrue(keys.contains(KeyEvent.KEYCODE_DPAD_RIGHT));
    }
}
