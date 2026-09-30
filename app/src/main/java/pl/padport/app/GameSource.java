package pl.padport.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read-only SAF tree. Keeps an index of document IDs, not a second copy of the game. */
final class GameSource {
    final Context context;
    final Uri tree;
    final String id;
    String title,engine,prefix="";
    /** RPG Maker XP/VX/VX Ace (RGSS): executable base name and RGSS version; null/0 for MV/MZ. */
    String exec;
    int rgss;
    final Map<String,Entry> entries=new LinkedHashMap<>();
    final Map<String,String> caseIndex=new HashMap<>();
    record Entry(String documentId,long size) {}
    private record Dir(String id,String path) {}

    GameSource(Context c,Uri uri) throws Exception {
        context=c;tree=uri;
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder name=new StringBuilder();
        for(int i=0;i<12;i++) name.append(String.format(Locale.ROOT,"%02x",digest[i]));
        id=name.toString();
    }
    String origin() {return "https://g"+id+".padport.local";}
    void scan() throws Exception {
        entries.clear();caseIndex.clear();
        ContentResolver resolver=context.getContentResolver();
        ArrayDeque<Dir> queue=new ArrayDeque<>();
        queue.add(new Dir(DocumentsContract.getTreeDocumentId(tree),""));
        Set<String> visited=new HashSet<>();
        while(!queue.isEmpty()) {
            Dir dir=queue.remove();
            if(!visited.add(dir.id())) continue;
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,dir.id());
            String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
            try(Cursor cursor=resolver.query(children,columns,null,null,null)) {
                if(cursor==null) throw new IOException(AppLanguage.text(context,R.string.folder_read_error));
                while(cursor.moveToNext()) {
                    String doc=cursor.getString(0), name=cursor.getString(1), type=cursor.getString(2);
                    String path=dir.path()+name;
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) queue.add(new Dir(doc,path+"/"));
                    else {
                        entries.put(path,new Entry(doc,cursor.isNull(3)?-1:cursor.getLong(3)));
                        String lower=path.toLowerCase(Locale.ROOT);
                        if(caseIndex.containsKey(lower)) caseIndex.put(lower,"");
                        else caseIndex.put(lower,path);
                    }
                }
            }
        }
        prefix="";exec=null;rgss=0;
        if(find("index.html")==null && find("www/index.html")==null) {
            RgssGame.Info info=RgssGame.detect(new RgssGame.Files(){
                public Collection<String> paths(){return entries.keySet();}
                public byte[] read(String path) throws IOException {return GameSource.this.read(path,1024*1024);}
            });
            if(info!=null) {
                exec=info.exec();rgss=info.version();engine=info.engine();title=info.title();
                saveIndex();
                return;
            }
        }
        if(find("index.html")==null) {
            if(find("www/index.html")!=null) prefix="www/";
            else throw new IOException(AppLanguage.text(context,R.string.index_missing));
        }
        engine=find(prefix+"js/rmmz_core.js")!=null?"MZ":find(prefix+"js/rpg_core.js")!=null?"MV":null;
        if(engine==null) throw new IOException(AppLanguage.text(context,R.string.unsupported_engine));
        JSONObject system=new JSONObject(new String(read("data/System.json",8*1024*1024),StandardCharsets.UTF_8));
        title=system.optString("gameTitle","RPG Maker "+engine);
        if(title.trim().isEmpty()) title="RPG Maker "+engine;
        saveIndex();
    }
    private Entry find(String name) {
        Entry entry=entries.get(name);
        if(entry!=null) return entry;
        String original=caseIndex.get(name.toLowerCase(Locale.ROOT));
        return original==null || original.isEmpty()?null:entries.get(original);
    }
    Entry entry(String relative) {
        String path=prefix+AssetPaths.normalize(relative);
        Entry result=find(path);
        return result!=null?result:find(AssetPaths.audioFallback(path));
    }
    String mimePath(String relative) {
        String path=prefix+AssetPaths.normalize(relative);
        return find(path)!=null?path:AssetPaths.audioFallback(path);
    }
    InputStream open(String relative) throws IOException {
        Entry entry=entry(relative);
        if(entry==null) throw new FileNotFoundException(relative);
        InputStream stream=context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,entry.documentId()));
        if(stream==null) throw new IOException(AppLanguage.text(context,R.string.file_open_error,relative));
        return stream;
    }
    byte[] read(String relative,int limit) throws IOException {
        try(InputStream in=open(relative)) {return readBounded(context,in,limit);}
    }
    static byte[] readBounded(InputStream in,int limit) throws IOException {
        return readBounded(null,in,limit);
    }
    static byte[] readBounded(Context context,InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[16384];int n;
        while((n=in.read(buffer))!=-1) {
            if(out.size()+n>limit) throw new IOException(context==null?"File exceeds limit of "+limit+" bytes":AppLanguage.text(context,R.string.file_too_large,limit));
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    JSONObject metadata() throws Exception {
        JSONObject result=new JSONObject().put("id",id).put("uri",tree.toString()).put("title",title).put("engine",engine).put("prefix",prefix);
        if(exec!=null) result.put("exec",exec).put("rgss",rgss);
        return result;
    }
    boolean isRgss(){return exec!=null;}
    int rgssVersion(){return rgss;}
    private File indexFile() {return new File(context.getFilesDir(),"index-"+id+".json");}
    private void saveIndex() throws Exception {
        JSONObject obj=metadata();JSONArray files=new JSONArray();
        for(var e:entries.entrySet()) files.put(new JSONArray().put(e.getKey()).put(e.getValue().documentId()).put(e.getValue().size()));
        obj.put("files",files);
        File tmp=new File(indexFile()+".tmp");
        try(OutputStream out=new FileOutputStream(tmp)) {out.write(obj.toString().getBytes(StandardCharsets.UTF_8));}
        java.nio.file.Files.move(tmp.toPath(),indexFile().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    static GameSource restore(Context c,JSONObject metadata) throws Exception {
        GameSource s=new GameSource(c,Uri.parse(metadata.getString("uri")));
        JSONObject index;
        try(InputStream in=new FileInputStream(s.indexFile())) {index=new JSONObject(new String(readBounded(c,in,32*1024*1024),StandardCharsets.UTF_8));}
        s.title=index.getString("title");s.engine=index.getString("engine");s.prefix=index.getString("prefix");
        s.exec=index.has("exec")?index.getString("exec"):null;s.rgss=index.optInt("rgss",0);
        JSONArray files=index.getJSONArray("files");
        for(int i=0;i<files.length();i++) {
            JSONArray entry=files.getJSONArray(i);String path=entry.getString(0);
            s.entries.put(path,new Entry(entry.getString(1),entry.getLong(2)));
            String lower=path.toLowerCase(Locale.ROOT);
            if(s.caseIndex.containsKey(lower)) s.caseIndex.put(lower,""); else s.caseIndex.put(lower,path);
        }
        // Fail early for revoked folder permissions, instead of leaving a black screen.
        try(InputStream ignored=s.open(s.isRgss()?s.exec+".ini":"index.html")) { }
        return s;
    }
}
