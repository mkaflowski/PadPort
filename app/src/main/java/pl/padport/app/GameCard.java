package pl.padport.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/** Artwork stays behind the controls; a gradient keeps text readable. */
final class GameCard extends FrameLayout {
    final JSONObject game;
    final ImageView picture;
    boolean loading,finished;
    private final TextView placeholder;

    GameCard(Activity activity,JSONObject game,Runnable play,Runnable options){
        super(activity);this.game=game;
        setBackground(Ui.background(Ui.CARD,Ui.dp(activity,18)));setClipToOutline(true);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,Ui.dp(activity,224));
        size.setMargins(0,Ui.dp(activity,12),0,Ui.dp(activity,6));setLayoutParams(size);
        picture=new ImageView(activity);picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ArtworkEffects.apply(picture,activity);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(picture,new FrameLayout.LayoutParams(-1,-1));
        String title=game.optString("title");
        placeholder=Ui.text(activity,title.isEmpty()?"?":title.substring(0,title.offsetByCodePoints(0,1)),90,Ui.ACCENT);
        placeholder.setAlpha(.12f);placeholder.setGravity(Gravity.END|Gravity.TOP);placeholder.setPadding(0,0,Ui.dp(activity,20),0);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x081d1c1d,0x661d1c1d,0xf21d1c1d}));
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=Ui.column(activity);content.setPadding(Ui.dp(activity,16),Ui.dp(activity,10),Ui.dp(activity,16),Ui.dp(activity,14));
        TextView name=Ui.text(activity,title,23,Ui.TEXT);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(Ui.dp(activity,3),0,Ui.dp(activity,1),Color.BLACK);content.addView(name);
        TextView details=Ui.text(activity,activity.getString(R.string.game_details,game.optString("engine")),12,Ui.TEXT);
        details.setMaxLines(1);details.setEllipsize(TextUtils.TruncateAt.END);details.setShadowLayer(Ui.dp(activity,2),0,1,Color.BLACK);content.addView(details);
        LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER_VERTICAL);
        Button launch=Ui.playButton(activity,play);
        actions.addView(launch,new LinearLayout.LayoutParams(Ui.dp(activity,128),Ui.dp(activity,48)));
        actions.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        Button more=Ui.button(activity,"⋮",options);more.setTextSize(24);more.setContentDescription(activity.getString(R.string.game_options,title));
        more.setBackgroundTintList(ColorStateList.valueOf(0xc01d1c1d));actions.addView(more,new LinearLayout.LayoutParams(Ui.dp(activity,52),Ui.dp(activity,48)));
        content.addView(actions);
        addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        if(DualScreenProfile.enabled(game)){
            ImageView badge=new ImageView(activity);badge.setImageResource(R.drawable.ic_dual_screen);
            badge.setContentDescription(activity.getString(R.string.dual_screen_badge));
            badge.setTooltipText(activity.getString(R.string.dual_screen_badge));
            badge.setPadding(Ui.dp(activity,7),Ui.dp(activity,7),Ui.dp(activity,7),Ui.dp(activity,7));
            badge.setBackground(Ui.background(0xdd1d1c1d,Ui.dp(activity,8)));
            FrameLayout.LayoutParams badgeSize=new FrameLayout.LayoutParams(Ui.dp(activity,40),Ui.dp(activity,40),Gravity.TOP|Gravity.END);
            badgeSize.setMargins(0,Ui.dp(activity,10),Ui.dp(activity,10),0);addView(badge,badgeSize);
        }
    }
    boolean visible(){return isAttachedToWindow()&&getGlobalVisibleRect(new Rect());}
    void artwork(Bitmap bitmap){picture.setImageBitmap(bitmap);placeholder.setVisibility(bitmap==null?VISIBLE:INVISIBLE);finished=true;}
    void releaseArtwork(){
        if(picture.getDrawable()!=null){picture.setImageDrawable(null);placeholder.setVisibility(VISIBLE);finished=false;}
    }
}
