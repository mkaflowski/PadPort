package pl.padport.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Transparent multitouch overlay; gestures starting outside its controls pass to the game. */
final class VirtualControllerView extends View {
    private final ControllerHub hub;
    private final VirtualPadState state;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private VirtualPadLayout layout;

    VirtualControllerView(Context context,ControllerHub hub){
        super(context);this.hub=hub;state=hub.virtualPad;
        setAlpha(VirtualControllerSettings.alpha(context));
        setFocusable(false);setContentDescription(context.getString(R.string.virtual_controller_name));
        setVisibility(GONE);
    }
    void configure(boolean enabled,boolean interactive){
        setAlpha(VirtualControllerSettings.alpha(getContext()));
        state.enabled(enabled);state.paused(!interactive);
        setVisibility(enabled?VISIBLE:GONE);hub.virtualChanged();invalidate();
    }
    void suspend(){state.paused(true);hub.virtualChanged();invalidate();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        state.clear();layout=new VirtualPadLayout(w,h,getResources().getDisplayMetrics().density);
        hub.virtualChanged();
    }
    @Override protected void onDetachedFromWindow(){state.clear();hub.virtualChanged();super.onDetachedFromWindow();}

    @Override public boolean onTouchEvent(MotionEvent event){
        if(layout==null||!state.enabled())return false;
        int action=event.getActionMasked(),at=event.getActionIndex();
        int before=state.buttons();
        if(action==MotionEvent.ACTION_DOWN){
            if(!layout.contains(event.getX(),event.getY()))return false;
            state.clear();
        }
        if(!state.interactive())return layout.contains(event.getX(at),event.getY(at));
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            if(layout.contains(event.getX(at),event.getY(at)))state.touch(event.getPointerId(at),layout.maskAt(event.getX(at),event.getY(at)));
        }else if(action==MotionEvent.ACTION_MOVE){
            for(int i=0;i<event.getPointerCount();i++)if(state.tracks(event.getPointerId(i)))
                state.touch(event.getPointerId(i),layout.maskAt(event.getX(i),event.getY(i)));
        }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
            state.release(event.getPointerId(at));
        }else if(action==MotionEvent.ACTION_CANCEL)state.clear();
        if(before!=state.buttons()){hub.virtualChanged();invalidate();}
        if(action==MotionEvent.ACTION_UP)performClick();
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(layout==null)return;
        float u=layout.unit,cx=layout.dpadX,cy=layout.dpadY,r=layout.dpadRadius;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x33000000);
        canvas.drawRoundRect(cx-r,cy-r,cx+r,cy+r,16*u,16*u,paint);
        drawButton(canvas,new VirtualPadLayout.Button("↑",12,cx-21*u,cy-64*u,cx+21*u,cy-22*u,false),u);
        drawButton(canvas,new VirtualPadLayout.Button("↓",13,cx-21*u,cy+22*u,cx+21*u,cy+64*u,false),u);
        drawButton(canvas,new VirtualPadLayout.Button("←",14,cx-64*u,cy-21*u,cx-22*u,cy+21*u,false),u);
        drawButton(canvas,new VirtualPadLayout.Button("→",15,cx+22*u,cy-21*u,cx+64*u,cy+21*u,false),u);
        for(VirtualPadLayout.Button button:layout.buttons)drawButton(canvas,button,u);
    }
    private void drawButton(Canvas canvas,VirtualPadLayout.Button button,float unit){
        boolean down=state.button(button.index())>.5f;
        float radius=button.round()?(button.right()-button.left())/2:9*unit;
        paint.setStyle(Paint.Style.FILL);paint.setColor(down?0xbbd16197:0x661d1c1d);
        canvas.drawRoundRect(button.left(),button.top(),button.right(),button.bottom(),radius,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*unit);paint.setColor(down?0xeed16197:0x99f6f6f6);
        canvas.drawRoundRect(button.left(),button.top(),button.right(),button.bottom(),radius,radius,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(down?0xff1d1c1d:0xddf6f6f6);
        paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize((button.label().length()>2?11:18)*unit);paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(button.label(),button.centerX(),button.centerY()-(paint.ascent()+paint.descent())/2,paint);
    }
}
