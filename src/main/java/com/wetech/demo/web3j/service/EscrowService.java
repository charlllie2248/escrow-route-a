package com.wetech.demo.web3j.service;

import com.wetech.demo.web3j.contracts.escrow.Escrow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.gas.ContractGasProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EscrowService {
    private static final String[] STATUS = {
            "Created", "Paid", "Completed", "Refunded", "Disputed"
    };
    private static final BigInteger LOG_BATCH_SIZE = BigInteger.TEN;

    private final Web3j web3j;
    private final Credentials credentials;
    private final ContractGasProvider gasProvider;

    private Escrow contract;
    private BigInteger deployBlock;

    public Map<String, String> deploy() throws Exception {
        // The server account becomes the arbiter of this new Escrow.
        Escrow deployed = Escrow.deploy(
                web3j, credentials, gasProvider, credentials.getAddress()).send();
        TransactionReceipt receipt = deployed.getTransactionReceipt()
                .orElseThrow(() -> new IllegalStateException("Deployment receipt missing"));
        this.contract = deployed;
        this.deployBlock = receipt.getBlockNumber();
        return Map.of(
                "contractAddress", deployed.getContractAddress(),
                "transactionHash", receipt.getTransactionHash(),
                "deployBlock", deployBlock.toString());
    }

    public Map<String, String> load(String address, BigInteger fromBlock) {
        if (address == null || !address.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("address must be a 0x-prefixed 40-digit address");
        }
        requireNonNegative(fromBlock, "fromBlock");
        this.contract = Escrow.load(address, web3j, credentials, gasProvider);
        this.deployBlock = fromBlock;
        return Map.of("contractAddress", contract.getContractAddress());
    }

    public Map<String, String> address() throws Exception {
        Escrow active = requireContract();
        return Map.of(
                "contractAddress", active.getContractAddress(),
                "arbiter", active.arbiter().send());
    }

    public Map<String, String> getOrder(BigInteger id) throws Exception {
        requireNonNegative(id, "id");
        Escrow.Order order = requireContract().getOrder(id).send();
        int status = order.status.intValueExact();
        if (status < 0 || status >= STATUS.length) {
            throw new IllegalStateException("Unknown order status: " + order.status);
        }
        return Map.of(
                "buyer", order.buyer,
                "seller", order.seller,
                "token", order.token,
                "amount", order.amount.toString(),
                "deadline", order.deadline.toString(),
                "status", STATUS[status],
                "description", order.description);
    }

    public List<Map<String, String>> listOrders() throws Exception {
        Escrow active = requireContract();
        var blockResponse = web3j.ethBlockNumber().send();
        if (blockResponse.hasError()) {
            throw new IOException("eth_blockNumber failed: "
                    + blockResponse.getError().getMessage());
        }
        BigInteger latest = blockResponse.getBlockNumber();
        List<Map<String, String>> orders = new ArrayList<>();

        // POTOS: query at most 10 blocks per request, including both endpoints.
        for (BigInteger from = deployBlock; from.compareTo(latest) <= 0;
                from = from.add(LOG_BATCH_SIZE)) {
            BigInteger to = from.add(LOG_BATCH_SIZE).subtract(BigInteger.ONE).min(latest);
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(from),
                    DefaultBlockParameter.valueOf(to),
                    active.getContractAddress());
            filter.addSingleTopic(EventEncoder.encode(Escrow.ORDERCREATED_EVENT));

            EthLog response = web3j.ethGetLogs(filter).send();
            if (response.hasError()) {
                throw new IOException("eth_getLogs failed for blocks " + from + "-" + to
                        + ": " + response.getError().getMessage());
            }
            for (var result : response.getLogs()) {
                Log log = (Log) result.get();
                Escrow.OrderCreatedEventResponse event =
                        Escrow.getOrderCreatedEventFromLog(log);
                orders.add(Map.of(
                        "id", event.id.toString(),
                        "buyer", event.buyer,
                        "seller", event.seller,
                        "amount", event.amount.toString(),
                        "blockNumber", log.getBlockNumber().toString(),
                        "transactionHash", log.getTransactionHash()));
            }
        }
        return orders;
    }

    public TransactionReceipt resolve(BigInteger id, boolean sellerWins) throws Exception {
        requireNonNegative(id, "id");
        // Let the contract check state and permissions, including the required
        // failure test: attempting to resolve a Paid (non-Disputed) order.
        return requireContract().resolve(id, sellerWins).send();
    }

    private Escrow requireContract() {
        if (contract == null) {
            throw new IllegalStateException("Call /api/escrow/deploy or /load first");
        }
        return contract;
    }

    private static void requireNonNegative(BigInteger value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
    }
}
