package pl.padport.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class Ui {
    // Palette: octet.design "Personalized app" - #f6f6f6 #6d6a6b #5c5a59 #4d4847 #1d1c1d + accent
    // (the theme in res/values/styles.xml uses the same values). The palette's accent
    // #907783 (hue 331, 10 % saturation) is made more intense: ACCENT #D16197 (same hue,
    // 55 %), 4.75:1 on BG, so dark text on it (the focused Play button) stays readable. On CARD
    // it has only 2.5:1, so text uses ACCENT_TEXT (#D16197 + 35 % #f6f6f6: 7.4:1 on BG,
    // 3.9:1 on CARD). MUTED is #f6f6f6 + 35 % #907783 (10.6:1 on BG, 5.6:1 on CARD) -
    // the palette greys are too dark for text on cards (#6d6a6b on #4d4847 = 1.7:1).
    static final int BG=0xFF1D1C1D,CARD=0xFF4D4847,SURFACE=0xFF5C5A59,OUTLINE=0xFF6D6A6B,
        TEXT=0xFFF6F6F6,MUTED=0xFFD2CACE,ACCENT=0xFFD16197,ACCENT_TEXT=0xFFDE95B8;
    static int dp(Activity a,int value) {return Math.round(value*a.getResources().getDisplayMetrics().density);}
    static LinearLayout screen(Activity a) {
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=column(a);root.setPadding(dp(a,22),dp(a,28),dp(a,22),dp(a,24));scroll.addView(root);
        // Handle cutouts/status/navigation bars, including edge-to-edge on Android 15.
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        a.setContentView(scroll);return root;
    }
    static LinearLayout column(Activity a) {LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout dialogContent(Activity a,LinearLayout content) {
        // Match the horizontal inset of the native Material dialog's list items.
        if(content.getChildCount()>0)content.setPadding(dp(a,24),0,dp(a,24),dp(a,8));
        return content;
    }
    static AlertDialog menuDialog(Activity a,String title,String[] items,LinearLayout settings,DialogInterface.OnClickListener action) {
        // One scrolling column: AlertDialog.setItems + setView creates separate panels
        // that compete for height and can leave the checkboxes below the visible area.
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content=column(a);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setView(scroll).create();
        TypedValue selectable=new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,selectable,true);
        for(int i=0;i<items.length;i++){
            final int index=i;
            TextView row=new TextView(a);row.setText(items[i]);row.setTextSize(18);row.setTextColor(TEXT);
            row.setGravity(Gravity.CENTER_VERTICAL);row.setMinHeight(dp(a,48));
            row.setPadding(dp(a,24),dp(a,12),dp(a,24),dp(a,12));
            row.setFocusable(true);row.setClickable(true);
            if(selectable.resourceId!=0)row.setBackgroundResource(selectable.resourceId);
            row.setOnClickListener(v->{action.onClick(dialog,index);dialog.dismiss();});
            content.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        if(settings!=null&&settings.getChildCount()>0)content.addView(dialogContent(a,settings),new LinearLayout.LayoutParams(-1,-2));
        return dialog;
    }
    static TextView text(Activity a,String value,int size,int color) {
        TextView t=new TextView(a);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setPadding(0,dp(a,6),0,dp(a,8));return t;
    }
    static TextView text(Activity a,int resource,int size,int color){return text(a,a.getString(resource),size,color);}
    static TextView title(Activity a,String value) {TextView t=text(a,value,30,TEXT);t.setTypeface(null,Typeface.BOLD);return t;}
    static TextView title(Activity a,int resource){return title(a,a.getString(resource));}
    static Button button(Activity a,String label,Runnable action) {
        Button b=new Button(a);b.setText(label);b.setAllCaps(false);b.setTextColor(TEXT);b.setOnClickListener(v->action.run());return b;
    }
    static Button button(Activity a,int resource,Runnable action){return button(a,a.getString(resource),action);}
    static Button playButton(Activity a,Runnable action) {
        Button b=button(a,R.string.play,action);
        int[][] states={
            {-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
            {android.R.attr.state_focused}, {android.R.attr.state_selected},
            {android.R.attr.state_activated}, {android.R.attr.state_hovered}, {}
        };
        int softOutline=(ACCENT&0x00ffffff)|0x99000000;
        int[] fills={BG,ACCENT_TEXT,ACCENT,ACCENT,ACCENT,CARD,BG};
        int[] strokes={OUTLINE,ACCENT_TEXT,ACCENT_TEXT,ACCENT_TEXT,ACCENT_TEXT,ACCENT,softOutline};
        ColorStateList foreground=new ColorStateList(states,new int[]{OUTLINE,BG,BG,BG,BG,TEXT,ACCENT_TEXT});
        StateListDrawable surfaces=new StateListDrawable();
        for(int i=0;i<states.length;i++){
            GradientDrawable shape=background(fills[i],dp(a,24));
            shape.setStroke(dp(a,i>=1&&i<=4?2:1),strokes[i]);
            surfaces.addState(states[i],shape);
        }
        // Text-only pill: subtle outline, native touch feedback and a filled controller focus.
        RippleDrawable background=new RippleDrawable(ColorStateList.valueOf((TEXT&0x00ffffff)|0x24000000),surfaces,background(TEXT,dp(a,24)));
        b.setBackgroundTintList(null);b.setBackground(background);b.setStateListAnimator(null);b.setElevation(0);
        b.setTextColor(foreground);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextSize(16);
        b.setIncludeFontPadding(false);b.setLetterSpacing(.02f);b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);b.setPadding(dp(a,24),0,dp(a,24),0);b.setFocusable(true);
        return b;
    }
    static GradientDrawable background(int color,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static LinearLayout card(Activity a) {
        LinearLayout c=column(a);c.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,14));c.setBackground(background(CARD,dp(a,18)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(a,12),0,dp(a,6));c.setLayoutParams(p);return c;
    }
}
