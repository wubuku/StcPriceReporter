package org.starcoin.stcpricereporter.taskservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.starcoin.bean.TypeObj;
import org.starcoin.stcpricereporter.data.model.PriceFeed;
import org.starcoin.stcpricereporter.data.repo.PriceFeedRepository;
import org.starcoin.stcpricereporter.utils.JsonRpcUtils;
import org.starcoin.stcpricereporter.utils.OnChainTransactionUtils;
import org.starcoin.types.AccountAddress;
import org.starcoin.types.Ed25519PrivateKey;
import org.starcoin.types.TransactionPayload;
import org.starcoin.types.TypeTag;
import org.starcoin.utils.AccountAddressUtils;
import org.starcoin.utils.SignatureUtils;
import org.starcoin.utils.StarcoinClient;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class OnChainManager {
    private final Logger LOG = LoggerFactory.getLogger(OnChainManager.class);

    private static final String FUNCTION_ID_IS_DATA_SOURCE_INITIALIZED = "0x00000000000000000000000000000001::PriceOracle::is_data_source_initialized";

    private final String senderAddressHex;// = "0x07fa08a855753f0ff7292fdcbe871216";
    private final String senderPrivateKeyHex;
    private final String starcoinRpcUrl;
    private final Integer starcoinChainId;
    private final String oracleScriptsAddressHex;// = "0x00000000000000000000000000000001";
    private final StarcoinClient starcoinClient;
    //"0x07fa08a855753f0ff7292fdcbe871216";

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private PriceFeedRepository priceFeedRepository;

    public OnChainManager(@Value("${starcoin.stc-price-reporter.sender-address}") String senderAddressHex,
                          @Value("${starcoin.stc-price-reporter.sender-private-key}") String senderPrivateKeyHex,
                          @Value("${starcoin.rpc-url}") String starcoinRpcUrl,
                          @Value("${starcoin.chain-id}") Integer starcoinChainId,
                          @Value("${starcoin.stc-price-reporter.oracle-scripts-address}") String oracleScriptsAddressHex) {
        this.senderAddressHex = senderAddressHex;
        this.senderPrivateKeyHex = senderPrivateKeyHex;
        this.starcoinRpcUrl = starcoinRpcUrl;
        this.starcoinChainId = starcoinChainId;
        this.oracleScriptsAddressHex = oracleScriptsAddressHex;
        this.starcoinClient = new StarcoinClient(this.starcoinRpcUrl, this.starcoinChainId);
    }

    public void initDataSourceOrUpdateOnChain(OffChainPriceCache<?> offChainPriceCache,
                                              PriceOracleType priceOracleType, BigInteger price) {
        if (offChainPriceCache.isFirstUpdate()) {
            if (!isDataSourceInitialize(priceOracleType)) {
                LOG.debug("Init data-source first.");
                initDataSource(priceOracleType, price);
                //updateOnChain(priceOracleType, price);
            } else {
                updateOnChain(priceOracleType, price);
            }
        } else {
            updateOnChain(priceOracleType, price);
        }
        // save in database
        String tokenPairId = priceOracleType.getStructName();
        PriceFeed priceFeed = priceFeedRepository.findById(tokenPairId).orElse(null);
        if (priceFeed == null) {
            priceFeed = new PriceFeed();
            priceFeed.setPairId(tokenPairId);
            //priceFeed.setPairName();
            //priceFeed.setDecimals();//todo
            //priceFeed.setDeviationPercentage();
            //priceFeed.setHeartbeatHours();
            priceFeed.setCreatedAt(System.currentTimeMillis());
            priceFeed.setCreatedBy("ADMIN");
            priceFeed.setUpdatedAt(priceFeed.getCreatedAt());
            priceFeed.setUpdatedBy(priceFeed.getCreatedBy());
        } else {
            priceFeed.setUpdatedAt(System.currentTimeMillis());
            priceFeed.setUpdatedBy("ADMIN");
        }
        priceFeed.setLatestPrice(price);
        priceFeedRepository.save(priceFeed);
    }

    public void updateOnChain(PriceOracleType priceOracleType, BigInteger price) {
        submitOracleTransaction(priceOracleType, oracleTypeTag ->
                OnChainTransactionUtils.encodePriceOracleUpdateScriptFunction(oracleTypeTag,
                        price, oracleScriptsAddressHex));
    }

    public boolean isDataSourceInitialize(PriceOracleType priceOracleType) {
        Object resultObj = contractCallV2(
                FUNCTION_ID_IS_DATA_SOURCE_INITIALIZED,
                Collections.singletonList(getTypeArgString(priceOracleType)),
                Collections.singletonList(senderAddressHex));
        return Boolean.parseBoolean(((List<Object>) resultObj).get(0).toString());
    }


    private String getTypeArgString(PriceOracleType priceOracleType) {
        return priceOracleType.getModuleAddress()
                + "::" + priceOracleType.getModuleName()
                + "::" + priceOracleType.getStructName();
    }

    /**
     * curl --location --request POST 'http://localhost:9850' \
     * --header 'Content-Type: application/json' \
     * --data-raw '{
     * "id":101,
     * "jsonrpc":"2.0",
     * "method":"contract.call_v2",
     * "params":[ {"function_id":"0x1::PriceOracle::is_data_source_initialized","type_args":["0x1::STCUSDOracle::STCUSD"],"args":["0x7beb045f2dea2f7fe50ede88c3e19a72"]}]
     * }'
     */
    private Object contractCallV2(String functionId, List<String> typeArgs, List<Object> args) {
        String method = "contract.call_v2";
        Map<String, Object> singleParamMap = new HashMap<>();
        singleParamMap.put("function_id", functionId);
        singleParamMap.put("type_args", typeArgs);
        singleParamMap.put("args", args);
        List<Object> params = Collections.singletonList(singleParamMap);
        Object resultObj = JsonRpcUtils.invoke(restTemplate, starcoinRpcUrl, method, params);
        if (LOG.isDebugEnabled()) {
            LOG.debug("contract.call_v2 {}, typeArgs: {}, args: {}, return result: {}", functionId, typeArgs, args, resultObj);
        }
        return resultObj;
    }

    public void initDataSource(PriceOracleType priceOracleType, BigInteger price) {
        submitOracleTransaction(priceOracleType, oracleTypeTag ->
                OnChainTransactionUtils.encodePriceOracleInitDataSourceScriptFunction(oracleTypeTag,
                        price, oracleScriptsAddressHex));
    }

    private void submitOracleTransaction(PriceOracleType priceOracleType,
                                         java.util.function.Function<TypeTag, TransactionPayload> transactionPayloadProvider) {
        TypeObj oracleTypeObject = TypeObj.builder()
                .moduleName(priceOracleType.getModuleName())
                .moduleAddress(priceOracleType.getModuleAddress())
                .name(priceOracleType.getStructName()).build();
        TypeTag oracleTypeTag = oracleTypeObject.toTypeTag();

        final Ed25519PrivateKey senderPrivateKey = SignatureUtils.strToPrivateKey(senderPrivateKeyHex);
        final AccountAddress senderAddress = AccountAddressUtils.create(senderAddressHex);
        TransactionPayload transactionPayload = transactionPayloadProvider.apply(oracleTypeTag);
        this.starcoinClient.submitTransaction(senderAddress, senderPrivateKey, transactionPayload);
    }

}