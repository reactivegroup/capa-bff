package group.rxcloud.capa.bff.hjson.invoke;

import com.alibaba.fastjson.JSONObject;
import group.rxcloud.capa.bff.domain.Context;
import group.rxcloud.capa.bff.hjson.domain.HJsonInvocationRequest;
import group.rxcloud.capa.bff.hjson.domain.HJsonInvocationResponse;
import group.rxcloud.capa.bff.hjson.json.JsonValueMapper;
import group.rxcloud.capa.bff.invoke.Invoke;
import group.rxcloud.capa.rpc.CapaRpcClient;
import group.rxcloud.cloudruntimes.domain.core.invocation.HttpExtension;
import group.rxcloud.cloudruntimes.utils.TypeRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.*;

/**
 * Author: KJ.ZHAO
 * Date: 2021/10/22 13:56
 */
@Component
public final class HJsonInvoker implements Invoke<HJsonInvocationRequest, HJsonInvocationResponse> {

    @Autowired
    private CapaRpcClient capaRpcClient;

    private final ThreadLocal<ConcurrentHashMap<String, List<HJsonInvocationRequest>>> localDynamicParamsMapping;

    private final ThreadLocal<ConcurrentHashMap<HJsonInvocationRequest, Integer>> serviceDynamicRequestParamCount;

    private final ThreadLocal<ConcurrentHashMap<String, Object>> paramsSet;

    private final ThreadLocal<CopyOnWriteArrayList<HJsonInvocationResponse>> responseList;

    private final ThreadLocal<CountDownLatch> cyclicBarrierThreadLocal;

    public List<HJsonInvocationResponse> invoke(List<HJsonInvocationRequest> invocationList, Context context) {
        clearThreadLocal();
        CountDownLatch cy = new CountDownLatch(invocationList.size());
        cyclicBarrierThreadLocal.set(cy);
        for (HJsonInvocationRequest request : invocationList) {
            allocateService(request);
        }
        try {
            boolean await = cy.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        List<HJsonInvocationResponse> res = responseList.get();

        return res;
    }

    private void clearThreadLocal() {
        cyclicBarrierThreadLocal.remove();
        localDynamicParamsMapping.remove();
        serviceDynamicRequestParamCount.remove();
        paramsSet.remove();
        responseList.remove();
    }

    public interface TaskService {

        void replaceParam(String key, Object value);

        boolean sync();
    }

    public static void main(String[] args) {
        Mono m = Mono.just("asd");
        Object block = m.block();
        System.out.println("finish");
    }
    public HJsonInvoker() {
        localDynamicParamsMapping = new ThreadLocal<>();

        serviceDynamicRequestParamCount = new ThreadLocal<>();

        paramsSet = new ThreadLocal<>();

        responseList = new ThreadLocal<>();

        cyclicBarrierThreadLocal = new ThreadLocal<>();

    }

    private void threadLocalReCheck() {
        if (localDynamicParamsMapping.get() == null) {
            ConcurrentHashMap<String, List<HJsonInvocationRequest>> tmpMap = new ConcurrentHashMap<>();
            localDynamicParamsMapping.set(tmpMap);
        }
        if (serviceDynamicRequestParamCount.get() == null) {
            ConcurrentHashMap<HJsonInvocationRequest, Integer> tmpMap2 = new ConcurrentHashMap<>();
            serviceDynamicRequestParamCount.set(tmpMap2);
        }
        if (paramsSet.get() == null) {
            ConcurrentHashMap<String, Object> tmpSet = new ConcurrentHashMap<>();
            paramsSet.set(tmpSet);
        }
        if (responseList.get() == null) {
            CopyOnWriteArrayList<HJsonInvocationResponse> re = new CopyOnWriteArrayList<>();
            responseList.set(re);
        }
    }

    private Mono allocateService(HJsonInvocationRequest taskService,
                                 ConcurrentHashMap<String, List<HJsonInvocationRequest>> localParamsServiceMapping,
                                 ConcurrentHashMap<HJsonInvocationRequest, Integer> serviceParamCountMapping,
                                 ConcurrentHashMap<String, Object> parasmKeyValueMapping,
                                 CopyOnWriteArrayList<HJsonInvocationResponse> reList,
                                 CountDownLatch cd){

        Map<String, Object> requiredParams = taskService.getRequiredParams();
        if(requiredParams!=null){

            boolean flag = false;
            Set<Map.Entry<String, Object>> entries = requiredParams.entrySet();
            for (Map.Entry<String, Object> param : entries) {
                //^#{[.]+}$
                if (param != null && !StringUtils.isEmpty(param.getKey()) ) {
                    if (parasmKeyValueMapping.containsKey(param.getKey())) {
                        taskService.replaceParam(param.getKey(), parasmKeyValueMapping.get(param.getKey()));
                        continue;
                    }
                    flag = true;
                    ConcurrentHashMap<String, List<HJsonInvocationRequest>> stringListHashMap = localParamsServiceMapping;
                    if (stringListHashMap.get(param.getKey()) == null) {
                        List<HJsonInvocationRequest> list = new ArrayList<>();
                        list.add(taskService);
                        Integer count = serviceParamCountMapping.get(taskService);
                        serviceParamCountMapping.put(taskService, count == null ? 1 : count + 1);
                        stringListHashMap.put(param.getKey(), list);
                    } else if (!stringListHashMap.get(param.getKey()).contains(taskService)) {
                        Integer count = serviceParamCountMapping.get(taskService);
                        serviceParamCountMapping.put(taskService, count == null ? 1 : count + 1);
                        stringListHashMap.get(param.getKey()).add(taskService);
                    }

                }
                ;
            }
            if (flag) {
                return null;
            }
        }
        // 扫描request是否含有 #{} 这种参数，有的话需要放在另外一个地方等待唤醒
//        CountDownLatch cd = cyclicBarrierThreadLocal.get();
        Mono<JSONObject> responseMono = capaRpcClient.invokeMethod(
                taskService.getAppId(),
                taskService.getMethod(),
                taskService.getData(),
                HttpExtension.POST,
                taskService.getMetadata(),
                TypeRef.get(JSONObject.class));

//        responseMono = Mono.create((s)->{});
        if (taskService.sync()) {

            JSONObject block = responseMono.block();
            reList.add(new HJsonInvocationResponse(taskService, block));
            Map<String, String> responseDataFormat = taskService.getResponseDataFormat();
            if (responseDataFormat!=null && !responseDataFormat.isEmpty()){
                Set<String> strings = responseDataFormat.keySet();
                for (String path:strings){
                    String nickName = responseDataFormat.get(path);
                    // 根据路径以及response对象，获取其value，然后将别名以及value映射放入paramsSet中
                    Object obj = JsonValueMapper.findValueByPointPath(block, path);

                    parasmKeyValueMapping.put(nickName,obj);

                    List<HJsonInvocationRequest> listTmp = localParamsServiceMapping.get(nickName);
                    localParamsServiceMapping.remove(nickName);
                    if (!CollectionUtils.isEmpty(listTmp)){
                        for (HJsonInvocationRequest re:listTmp){
                            Map<String, Object> pMap = new HashMap<>();
                            pMap.put(nickName,obj);
                            re.setData(JsonValueMapper.replaceValuesByParameters(re.getData(),pMap));
                            serviceParamCountMapping.put(re,serviceParamCountMapping.get(taskService)-1);
                            if (serviceParamCountMapping.get(re)==0){
                                allocateService(re,localParamsServiceMapping,serviceParamCountMapping,parasmKeyValueMapping,reList,cd);
                            }
                        }

                    }


                }
            }
            cd.countDown();
        } else {
            responseMono.doOnSuccess((s) -> {

                reList.add(new HJsonInvocationResponse(taskService, s));
                Map<String, String> responseDataFormat = taskService.getResponseDataFormat();
                if (responseDataFormat!=null && !responseDataFormat.isEmpty()){
                    Set<String> strings = responseDataFormat.keySet();
                    for (String path:strings){
                        String nickName = responseDataFormat.get(path);
                        // 根据路径以及response对象，获取其value，然后将别名以及value映射放入paramsSet中
                        Object obj = JsonValueMapper.findValueByPointPath(s, path);
                        parasmKeyValueMapping.put(nickName,obj);

                        List<HJsonInvocationRequest> listTmp = localParamsServiceMapping.get(nickName);
                        localParamsServiceMapping.remove(nickName);
                        if (!CollectionUtils.isEmpty(listTmp)){
                            for (HJsonInvocationRequest re:listTmp){
                                Map<String, Object> pMap = new HashMap<>();
                                pMap.put(nickName,obj);
                                re.setData(JsonValueMapper.replaceValuesByParameters(re.getData(),pMap));
                                serviceParamCountMapping.put(re,serviceParamCountMapping.get(taskService)-1);
                                if (serviceParamCountMapping.get(re)==0){
                                    allocateService(re,localParamsServiceMapping,serviceParamCountMapping,parasmKeyValueMapping,reList,cd);
                                }
                            }

                        }


                    }
                }
                cd.countDown();
            });

            responseMono.doOnError((s)->{
                s.printStackTrace();
                cd.countDown();

            });

        }


        return null;

    }


    public Mono allocateService(HJsonInvocationRequest taskService) {
        threadLocalReCheck();
        ConcurrentHashMap<String, List<HJsonInvocationRequest>> localParamsServiceMapping = localDynamicParamsMapping.get();
        ConcurrentHashMap<HJsonInvocationRequest, Integer> serviceParamCountMapping = serviceDynamicRequestParamCount.get();
        ConcurrentHashMap<String, Object> parasmKeyValueMapping = paramsSet.get();
        CopyOnWriteArrayList<HJsonInvocationResponse> reList = responseList.get();
// 扫描request是否含有 #{} 这种参数，有的话需要放在另外一个地方等待唤醒
        CountDownLatch cd = cyclicBarrierThreadLocal.get();
        return allocateService(taskService,localParamsServiceMapping,serviceParamCountMapping,parasmKeyValueMapping,reList,cd);

    }
}
