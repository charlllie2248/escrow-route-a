package com.wetech.demo.web3j.controller;

import com.wetech.demo.web3j.service.EscrowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/escrow")
@RequiredArgsConstructor
public class EscrowController {
    private final EscrowService escrowService;

    @PostMapping("/deploy")
    public Map<String, String> deploy() throws Exception {
        return escrowService.deploy();
    }

    @PostMapping("/load")
    public Map<String, String> load(
            @RequestParam("address") String address,
            @RequestParam("fromBlock") BigInteger fromBlock) {
        return escrowService.load(address, fromBlock);
    }

    @GetMapping("/address")
    public Map<String, String> address() throws Exception {
        return escrowService.address();
    }

    @GetMapping("/orders/{id}")
    public Map<String, String> getOrder(@PathVariable("id") BigInteger id) throws Exception {
        return escrowService.getOrder(id);
    }

    @GetMapping("/orders")
    public List<Map<String, String>> listOrders() throws Exception {
        return escrowService.listOrders();
    }

    @PostMapping("/orders/{id}/resolve")
    public Map<String, String> resolve(
            @PathVariable("id") BigInteger id,
            @RequestParam("sellerWins") boolean sellerWins) throws Exception {
        TransactionReceipt receipt = escrowService.resolve(id, sellerWins);
        return Map.of(
                "transactionHash", receipt.getTransactionHash(),
                "blockNumber", receipt.getBlockNumber().toString(),
                "gasUsed", receipt.getGasUsed().toString(),
                "status", receipt.getStatus());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exception", exception.getClass().getSimpleName());
        body.put("message", exception.getMessage() == null
                ? "Request failed; inspect the server log" : exception.getMessage());

        HttpStatus status = HttpStatus.BAD_GATEWAY;
        if (exception instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
        } else if (exception instanceof IllegalStateException) {
            status = HttpStatus.CONFLICT;
        } else if (exception instanceof TransactionException txException) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            // A failed on-chain receipt is distinct from a pre-broadcast error.
            txException.getTransactionHash().ifPresent(hash -> body.put("transactionHash", hash));
            txException.getTransactionReceipt().ifPresent(receipt -> {
                body.put("transactionHash", receipt.getTransactionHash());
                body.put("status", receipt.getStatus());
                body.put("blockNumber", receipt.getBlockNumber().toString());
                body.put("gasUsed", receipt.getGasUsed().toString());
            });
        }
        return ResponseEntity.status(status).body(body);
    }
}
