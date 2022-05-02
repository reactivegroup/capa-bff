package group.rxcloud.capa.bff.util;

import com.alibaba.fastjson.JSONObject;
import group.rxcloud.capa.bff.json.JsonValueMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;

/**
 * Author: KJ.ZHAO
 * Date: 2021/10/23 21:52
 */
public class InnerMethodUtil {

    private static final HashMap<String, InnerInvokeMethod> methodMap;

    public static void main(String[] args) {
        JSONObject j = new JSONObject();
        j.put("name", "zhangsan");
        j.put("age", 12);
        JSONObject com = new JSONObject();
        com.put("name", "xiehcneg");
        j.put("company", com);
        System.out.println("original: " + j);
        JSONObject com2 = new JSONObject();
        JSONObject depart01 = new JSONObject();
        depart01.put("dName", "depart01");
        com.put("depart", depart01);
        com2.put("name", "xiehcneg222");

        JsonValueMapper.replaceValueByRealPath(j, "company", com2);
        System.out.println("first :" + j);

        JsonValueMapper.replaceValueByRealPath(j, "company.name", " nmnmn");
        System.out.println("second: " + j);
    }

    static {
        methodMap = new HashMap<>();
        methodMap.put("base64", (bytes) -> Base64.getEncoder().encode(bytes));
        methodMap.put("tostring", (bytes) -> bytes);
    }

    public static String runMethodAsStringBeforeConvert(String methodName, Object target) {
        if (target == null || methodName == null || !methodName.contains("#{") || !methodName.contains("}")
                || methodName.indexOf("}") < methodName.indexOf("#{")) {
            return "";
        }
        methodName = methodName.substring(methodName.indexOf("#{") + 2, methodName.indexOf("}"));
        if (methodMap.get(methodName.toLowerCase()) == null) {
            return JSONObject.toJSONString(target);
        }
        byte[] bytes = JSONObject.toJSONString(target).getBytes(StandardCharsets.UTF_8);
        return new String(methodMap.get(methodName.toLowerCase()).run(bytes), StandardCharsets.UTF_8);
    }


}