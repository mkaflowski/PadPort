package pl.padport.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PadMathTest {
    @Test public void centerAndFlatAreNeutral(){
        assertEquals(0,PadMath.stick(.09f,-1,1,.1f,.05f),0);
        assertEquals(0,PadMath.stick(-.14f,-1,1,0,.15f),0);
    }
    @Test public void fullTravelRemainsFullTravel(){
        assertEquals(1,PadMath.stick(1,-1,1,0,.2f),.0001);
        assertEquals(-1,PadMath.stick(-1,-1,1,0,.2f),.0001);
        assertEquals(.5,PadMath.stick(.6f,-1,1,0,.2f),.0001);
    }
    @Test public void unsignedDeviceAxesHaveACenter(){
        assertEquals(0,PadMath.stick(127.5f,0,255,0,.15f),0);
        assertEquals(-1,PadMath.stick(0,0,255,0,.15f),0);
        assertEquals(1,PadMath.stick(255,0,255,0,.15f),0);
    }
    @Test public void triggersSupportSignedAndUnsignedRanges(){
        assertEquals(0,PadMath.trigger(-1,-1,1,0),0);
        assertEquals(.5,PadMath.trigger(0,-1,1,0),0);
        assertEquals(1,PadMath.trigger(1,-1,1,0),0);
        assertEquals(.8,PadMath.trigger(204,0,255,0),.0001);
    }
    @Test public void invalidAndOutOfRangeSamplesAreSafe(){
        assertEquals(0,PadMath.stick(Float.NaN,-1,1,0,.15f),0);
        assertEquals(0,PadMath.trigger(1,1,1,0),0);
        assertEquals(1,PadMath.trigger(10,0,1,0),0);
    }
    @Test public void digitalReleaseDoesNotCancelAnalogTriggerOrHat(){
        PadMath.State state=new PadMath.State();
        state.keys[6]=1;state.motion[6]=.8f;state.keys[6]=0;
        assertEquals(.8,state.button(6),.0001);
        state.keys[12]=1;state.motion[12]=1;state.motion[12]=0;
        assertEquals(1,state.button(12),0);
        state.clear();assertEquals(0,state.button(6),0);assertEquals(0,state.button(12),0);
    }
    @Test public void buttonRemappingSupportsKeyboardHidAndSwaps(){
        PadProfile profile=new PadProfile();
        assertEquals(0,profile.button(96)); // Android BUTTON_A
        assertEquals(1,profile.button(97)); // BUTTON_B
        profile.learn(97,0);
        assertEquals(0,profile.button(97));assertEquals(-1,profile.button(96));
        profile.learn(96,1);
        assertEquals(1,profile.button(96));assertEquals(0,profile.button(97));
        profile.learn(54,0); // keyboard Z, no synthetic keyboard event needed
        assertEquals(0,profile.button(54));assertEquals(-1,profile.button(97));
    }
    @Test public void digitalTriggerEventsDoNotDestroyAnalogPrecision(){
        PadMath.State state=new PadMath.State();state.keys[6]=1;state.motion[6]=.8f;state.analog[6]=true;
        assertEquals(.8,state.button(6),.0001);
        state.motion[6]=0;assertEquals(0,state.button(6),0);
        state.clear();state.keys[6]=1;assertEquals(1,state.button(6),0);
    }
}
