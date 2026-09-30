package pl.padport.app;

/** Responsive hit areas in standard Gamepad button order. */
final class VirtualPadLayout {
    record Button(String label,int index,float left,float top,float right,float bottom,boolean round){
        boolean contains(float x,float y){return x>=left&&x<=right&&y>=top&&y<=bottom;}
        float centerX(){return (left+right)/2;}
        float centerY(){return (top+bottom)/2;}
    }
    final float unit,dpadX,dpadY,dpadRadius;
    final Button[] buttons;

    VirtualPadLayout(int width,int height,float density){
        unit=Math.max(.01f,Math.min(density,Math.min(width/480f,height/320f)));
        dpadX=100*unit;dpadY=height-94*unit;dpadRadius=66*unit;
        float right=width-100*unit,cy=height-94*unit,gap=48*unit,r=26*unit;
        buttons=new Button[]{
            circle("A",0,right,cy+gap,r),circle("B",1,right+gap,cy,r),
            circle("X",2,right-gap,cy,r),circle("Y",3,right,cy-gap,r),
            rect("L1",4,24*unit,height-218*unit,54*unit,34*unit),
            rect("L2",6,88*unit,height-218*unit,54*unit,34*unit),
            rect("R2",7,width-142*unit,height-218*unit,54*unit,34*unit),
            rect("R1",5,width-78*unit,height-218*unit,54*unit,34*unit),
            rect("SELECT",8,width/2f-64*unit,height-42*unit,56*unit,30*unit),
            rect("START",9,width/2f+8*unit,height-42*unit,56*unit,30*unit)
        };
    }
    private static Button circle(String label,int index,float x,float y,float r){return new Button(label,index,x-r,y-r,x+r,y+r,true);}
    private static Button rect(String label,int index,float x,float y,float w,float h){return new Button(label,index,x,y,x+w,y+h,false);}
    boolean inDpad(float x,float y){return Math.abs(x-dpadX)<=dpadRadius&&Math.abs(y-dpadY)<=dpadRadius;}
    boolean contains(float x,float y){return inDpad(x,y)||maskAt(x,y)!=0;}
    int maskAt(float x,float y){
        for(Button button:buttons)if(button.contains(x,y))return 1<<button.index();
        if(!inDpad(x,y))return 0;
        int mask=0;float dx=x-dpadX,dy=y-dpadY,dead=18*unit;
        if(dx < -dead)mask|=1<<14;else if(dx>dead)mask|=1<<15;
        if(dy < -dead)mask|=1<<12;else if(dy>dead)mask|=1<<13;
        return mask;
    }
}
