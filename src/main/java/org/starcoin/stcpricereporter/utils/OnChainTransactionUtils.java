package org.starcoin.stcpricereporter.utils;

import com.novi.bcs.BcsSerializer;
import com.novi.serde.Bytes;
import com.novi.serde.Int128;
import com.novi.serde.SerializationError;
import com.novi.serde.Unsigned;
import org.starcoin.stdlib.Helpers;
import org.starcoin.types.*;

import java.math.BigInteger;
import java.util.Arrays;

public class OnChainTransactionUtils {

    /**
     *  public(script) fun update<OracleT: copy+store+drop>(signer: signer, value: u128) {
     *         PriceOracle::update<OracleT>(&signer, value);
     *  }
     */
    public static TransactionPayload encodePriceOracleUpdateScriptFunction(TypeTag oracleType,
                                                                           @Unsigned @Int128 BigInteger price,
                                                                           String oracleScriptsAddress) {
        ScriptFunction.Builder script_function_builder = new ScriptFunction.Builder();
        script_function_builder.ty_args = Arrays.asList(oracleType);
        script_function_builder.args = Arrays.asList(encode_u128_argument(price));
        script_function_builder.function = new Identifier("update");

        script_function_builder.module = new ModuleId(AccountAddress.valueOf(//todo
                CommonUtils.hexToByteArray(oracleScriptsAddress)),
                new Identifier("PriceOracleScripts"));

        TransactionPayload.ScriptFunction.Builder builder = new TransactionPayload.ScriptFunction.Builder();
        builder.value = script_function_builder.build();
        return builder.build();
    }


    private static Bytes encode_u128_argument(@Unsigned @Int128 BigInteger arg) {
        try {
            BcsSerializer s = new BcsSerializer();
            s.serialize_u128(arg);
            return Bytes.valueOf(s.get_bytes());
        } catch (SerializationError var2) {
            throw new IllegalArgumentException("Unable to serialize argument of type u128");
        }
    }
}
