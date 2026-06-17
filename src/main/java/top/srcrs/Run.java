package top.srcrs;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;
import java.net.URLEncoder;
import java.util.*;

/**
 * 程序运行开始的地方
 * @author srcrs
 * @Time 2020-10-31
 */
public class Run
{
    /** 获取日志记录器对象 */
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /** 获取用户所有关注贴吧 */
    String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    /** 获取用户的tbs */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /** 贴吧签到接口 */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    /** 存储用户所关注的贴吧 */
    private List<String> follow = new ArrayList<>();
    /** 签到成功的贴吧列表 */
    private static List<String>  success = new ArrayList<>();
    /** 用户的tbs */
    private String tbs = "";
    /** 用户所关注的贴吧数量 */
    private static Integer followNum = 201;
    public static void main( String[] args ){
        Cookie cookie = Cookie.getInstance();
        // 存入Cookie，以备使用
        if(args.length==0){
            LOGGER.warn("请在Secrets中填写BDUSS");
        }
        cookie.setBDUSS(args[0]);
        Run run = new Run();
        run.getTbs();
        run.getFollow();
        run.runSign();
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {}",followNum,success.size(),followNum-success.size());
        
        // 检查并执行在指定帖子内回复4次消息的任务喵
        String tid = System.getenv("TID");
        if (tid != null && !tid.trim().isEmpty()) {
            String content = System.getenv("CONTENT");
            if (content == null || content.trim().isEmpty()) {
                content = "贴吧自动回复测试";
            }
            run.replyThread(tid, content);
        }

        if(args.length == 2){
            run.send(args[1]);
        }
    }

    /**
     * 进行登录，获得 tbs ，签到的时候需要用到这个参数
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getTbs(){
        try{
            JSONObject jsonObject = Request.get(TBS_URL);
            if("1".equals(jsonObject.getString("is_login"))){
                LOGGER.info("获取tbs成功");
                tbs = jsonObject.getString("tbs");
            } else{
                LOGGER.warn("获取tbs失败 -- " + jsonObject);
            }
        } catch (Exception e){
            LOGGER.error("获取tbs部分出现错误 -- " + e);
        }
    }

    /**
     * 获取用户所关注的贴吧列表
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow(){
        try{
            JSONObject jsonObject = Request.get(LIKE_URL);
            LOGGER.info("获取贴吧列表成功");
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
            followNum = jsonArray.size();
            // 获取用户所有关注的贴吧
            for (Object array : jsonArray) {
                if("0".equals(((JSONObject) array).getString("is_sign"))){
                    // 将为签到的贴吧加入到 follow 中，待签到
                    follow.add(((JSONObject) array).getString("forum_name").replace("+","%2B"));
                } else{
                    // 将已经成功签到的贴吧，加入到 success
                    success.add(((JSONObject) array).getString("forum_name"));
                }
            }
        } catch (Exception e){
            LOGGER.error("获取贴吧列表部分出现错误 -- " + e);
        }
    }

    /**
     * 开始进行签到，每一轮性将所有未签到的贴吧进行签到，一共进行5轮，如果还未签到完就立即结束
     * 一般一次只会有少数的贴吧未能完成签到，为了减少接口访问次数，每一轮签到完等待1分钟，如果在过程中所有贴吧签到完则结束。
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign(){
        // 当执行 5 轮所有贴吧还未签到成功就结束操作
        Integer flag = 5;
        try{
            while(success.size()<followNum&&flag>0){
                LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                LOGGER.info("还剩 {} 贴吧需要签到", followNum - success.size());
                Iterator<String> iterator = follow.iterator();
                while(iterator.hasNext()){
                    String s = iterator.next();
                    String rotation = s.replace("%2B","+");
                    String body = "kw="+s+"&tbs="+tbs+"&sign="+ Encryption.enCodeMd5("kw="+rotation+"tbs="+tbs+"tiebaclient!!!");
                    JSONObject post = Request.post(SIGN_URL, body);
                    if("0".equals(post.getString("error_code"))){
                        iterator.remove();
                        success.add(rotation);
                        LOGGER.info(rotation + ": " + "签到成功");
                    } else {
                        LOGGER.warn(rotation + ": " + "签到失败");
                    }
                }
                if (success.size() != followNum){
                    // 为防止短时间内多次请求接口，触发风控，设置每一轮签到完等待 5 分钟
                    Thread.sleep(1000 * 60 * 5);
                    /**
                     * 重新获取 tbs
                     * 尝试解决以前第 1 次签到失败，剩余 4 次循环都会失败的错误。
                     */
                    getTbs();
                }
                flag--;
            }
        } catch (Exception e){
            LOGGER.error("签到部分出现错误 -- " + e);
        }
    }

    /**
     * 发送运行结果到微信，通过 server 酱
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
    public void send(String sckey){
        /** 将要推送的数据 */
        String text = "总: "+ followNum + " - ";
        text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String desp = "共 "+ followNum + " 贴吧\n\n";
        desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String body = "text="+text+"&desp="+"TiebaSignIn运行结果\n\n"+desp;
        StringEntity entityBody = new StringEntity(body,"UTF-8");
        HttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://sc.ftqq.com/"+sckey+".send");
        httpPost.addHeader("Content-Type","application/x-www-form-urlencoded");
        httpPost.setEntity(entityBody);
        HttpResponse resp = null;
        String respContent = null;
        try{
            resp = client.execute(httpPost);
            HttpEntity entity=null;
            if(resp.getStatusLine().getStatusCode()<400){
                entity = resp.getEntity();
            } else{
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
            LOGGER.info("server酱推送正常");
        } catch (Exception e){
            LOGGER.error("server酱发送失败 -- " + e);
        }
    }

    /**
     * 根据帖子ID(TID)获取该帖子所属贴吧的论坛ID(fid)和名称(kw)喵
     * @param tid 帖子ID喵
     * @return 包含fid和kw的数组喵，若失败则返回null喵
     */
    private String[] getForumInfoByTid(String tid) {
        try {
            // 参数字典升序排序：kz, pn, rn
            String signStr = "kz=" + tid + "pn=1rn=1tiebaclient!!!";
            String sign = Encryption.enCodeMd5(signStr);
            String body = "kz=" + tid + "&pn=1&rn=1&sign=" + sign;
            
            // 发送网络请求喵
            JSONObject jsonObject = Request.post("http://c.tieba.baidu.com/c/f/pb/page", body);
            if (jsonObject != null) {
                JSONObject forum = jsonObject.getJSONObject("forum");
                if (forum != null) {
                    String fid = forum.getString("id");
                    String kw = forum.getString("name");
                    if (fid != null && !fid.isEmpty() && kw != null && !kw.isEmpty()) {
                        LOGGER.info("成功获取贴吧信息喵 -- 吧名: {} -- 吧ID: {}", kw, fid);
                        return new String[]{fid, kw};
                    }
                }
            }
            LOGGER.warn("获取贴吧信息失败喵 -- 返回数据: " + jsonObject);
        } catch (Exception e) {
            LOGGER.error("获取帖子关联贴吧信息时出现错误喵 -- " + e);
        }
        return null;
    }

    /**
     * 在指定帖子(TID)内发送4次消息喵
     * @param tid 帖子ID喵
     * @param rawContent 原始回复消息内容喵
     */
    public void replyThread(String tid, String rawContent) {
        try {
            // 1. 首先获取贴吧的fid和kw信息喵
            String[] forumInfo = getForumInfoByTid(tid);
            if (forumInfo == null) {
                LOGGER.warn("无法获取帖子对应的贴吧信息，停止自动回复喵！");
                return;
            }
            String fid = forumInfo[0];
            String kw = forumInfo[1];
            
            // 2. 循环发送4次消息喵
            for (int i = 1; i <= 4; i++) {
                // 每次回复前重新获取tbs以保证时效性喵
                getTbs();
                
                // 拼接可爱的回复内容喵，结尾带“喵”
                String contentWithSuffix = rawContent + "喵~ [" + i + "/4] (时间戳: " + System.currentTimeMillis() + ")";
                
                // 使用TreeMap自动对参数进行字典升序排序喵
                Map<String, String> params = new TreeMap<>();
                params.put("BDUSS", Cookie.getInstance().getBDUSS());
                params.put("_client_type", "2");
                params.put("_client_version", "8.9.8.8");
                params.put("content", contentWithSuffix);
                params.put("fid", fid);
                params.put("kw", kw);
                params.put("tid", tid);
                params.put("tbs", tbs);
                
                // 拼接签名串，形如 key1=value1key2=value2...tiebaclient!!!
                StringBuilder signBuilder = new StringBuilder();
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    signBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                }
                signBuilder.append("tiebaclient!!!");
                String sign = Encryption.enCodeMd5(signBuilder.toString());
                
                // 拼接请求body，注意所有参数的值都需要进行UTF-8 URL编码喵
                StringBuilder bodyBuilder = new StringBuilder();
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (bodyBuilder.length() > 0) {
                        bodyBuilder.append("&");
                    }
                    bodyBuilder.append(entry.getKey())
                               .append("=")
                               .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                }
                bodyBuilder.append("&sign=").append(sign);
                
                // 发送回复POST请求喵
                JSONObject responseJson = Request.post("http://c.tieba.baidu.com/c/c/post/add", bodyBuilder.toString());
                if (responseJson != null && "0".equals(responseJson.getString("error_code"))) {
                    LOGGER.info("第 {} 次回复发送成功喵！", i);
                } else {
                    LOGGER.warn("第 {} 次回复发送失败喵 -- 返回数据: " + responseJson, i);
                }
                
                // 为防止短时间内请求过于频繁被风控拦截，每次回复后等待8秒喵
                if (i < 4) {
                    Thread.sleep(8000);
                }
            }
        } catch (Exception e) {
            LOGGER.error("自动回复帖子的过程中出现错误喵 -- " + e);
        }
    }
}
