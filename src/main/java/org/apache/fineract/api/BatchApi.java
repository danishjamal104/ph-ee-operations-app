package org.apache.fineract.api;

import org.apache.fineract.file.FileTransferService;
import org.apache.fineract.operations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BatchApi {
    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    @Qualifier("awsStorage")
    private FileTransferService fileTransferService;

    @Value("${application.bucket-name}")
    private String bucketName;


    @GetMapping("/batch")
    public BatchDTO batchDetails(@RequestParam(value = "batchId", required = false) String batchId,
                                 @RequestParam(value = "requestId", required = false) String requestId) {
        Batch batch = batchRepository.findByBatchId(batchId);
        if (batch != null) {
            if (batch.getResultGeneratedAt() != null) {
//                Checks if last status was checked before 10 mins
                if (new Date().getTime() - batch.getResultGeneratedAt().getTime() < 600000) {
                    return generateDetails(batch);
                } else {
                    return generateDetails(batch);
                }
            } else {
                return generateDetails(batch);
            }
        } else {
            return null;
        }

    }

    @GetMapping("/batch/detail")
    public HashMap<String, Object> batchDetails(HttpServletResponse httpServletResponse,
                                                @RequestParam(value = "batchId") String batchId,
                                                @RequestParam(value = "status", defaultValue = "ALL") String status,
                                                @RequestParam(value = "pageNo", defaultValue = "0") Integer pageNo,
                                                @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        HashMap<String, Object> response = new HashMap<>();
        List<Transfer> transfers;

        if (status.equalsIgnoreCase(TransferStatus.COMPLETED.toString()) ||
                status.equalsIgnoreCase(TransferStatus.IN_PROGRESS.toString()) ||
                status.equalsIgnoreCase(TransferStatus.FAILED.toString())) {
            transfers = transferRepository.findAllByBatchIdAndStatus(batchId, status.toUpperCase(), new PageRequest(pageNo, pageSize));
        } else {
            transfers = transferRepository.findAllByBatchId(batchId, new PageRequest(pageNo, pageSize));
        }

        response.put("data", new ResponseEntity<List<Transfer>>(transfers, new HttpHeaders(), HttpStatus.OK));
        Batch batch = batchRepository.findByBatchId(batchId);

        if (batch.getResult_file() != null) {
            if (transfers.size() == 0) {
                httpServletResponse.setHeader("Location", batch.getResult_file());
                httpServletResponse.setStatus(302);
                return null;
            }
            response.put("reportFile", batch.getResult_file());
        }

        return response;
    }

    @GetMapping("/batch/transactions")
    public HashMap<String, String> batchTransactionDetails(@RequestParam String batchId) {
        Batch batch = batchRepository.findByBatchId(batchId);
        if (batch != null) {
            List<Transfer> transfers = transferRepository.findAllByBatchId(batch.getBatchId());
            HashMap<String, String> status = new HashMap<>();
            for(Transfer transfer: transfers){
                status.put(transfer.getTransactionId(), transfer.getStatus().name());
            }
            return status;
        } else {
            return null;
        }
    }

    private BatchDTO generateDetails (Batch batch) {

        List<Transfer> transfers = transferRepository.findAllByBatchId(batch.getBatchId());

        List<Batch> allBatches = batchRepository.findAllByBatchId(batch.getBatchId());

        Long completed = 0L;
        Long failed = 0L;
        Long total = 0L;
        Long ongoing = 0L;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal completedAmount = BigDecimal.ZERO;
        BigDecimal ongoingAmount = BigDecimal.ZERO;
        BigDecimal failedAmount = BigDecimal.ZERO;

        for (Transfer transfer : transfers) {
            total++;
            BigDecimal amount = transfer.getAmount();
            totalAmount = totalAmount.add(amount);
            if (transfer.getStatus().equals(TransferStatus.COMPLETED)) {
                completed++;
                completedAmount = completedAmount.add(amount);
            } else if (transfer.getStatus().equals(TransferStatus.FAILED)) {
                failed++;
                failedAmount = failedAmount.add(amount);
            } else if (transfer.getStatus().equals(TransferStatus.IN_PROGRESS)) {
                ongoing++;
                ongoingAmount = ongoingAmount.add(amount);
            }
        }

        // calculating matrices for sub batches
        Long subBatchFailed = 0L;
        Long subBatchCompleted = 0L;
        Long subBatchOngoing = 0L;
        Long subBatchTotal = 0L;

        for (Batch bt: allBatches) {
            if (bt.getSubBatchId() == null || bt.getSubBatchId().isEmpty()) {
                continue;
            }
            if (bt.getFailed() != null) {
                subBatchFailed += bt.getFailed();
                failedAmount = failedAmount.add(BigDecimal.valueOf(bt.getFailedAmount()));
            }
            if (bt.getCompleted() != null) {
                subBatchCompleted += bt.getCompleted();
                completedAmount = completedAmount.add(BigDecimal.valueOf(bt.getCompletedAmount()));
            }
            if (bt.getOngoing() != null) {
                subBatchOngoing += bt.getOngoing();
                ongoingAmount = ongoingAmount.add(BigDecimal.valueOf(bt.getOngoingAmount()));
            }
            if (bt.getTotalTransactions() != null) {
                subBatchTotal += bt.getTotalTransactions();
                totalAmount = totalAmount.add(BigDecimal.valueOf(bt.getTotalAmount()));
            }
        }

        // updating the data with sub batches details
        completed += subBatchCompleted;
        failed += subBatchFailed;
        total += subBatchTotal;
        ongoing += subBatchOngoing;

        if (batch.getResult_file() == null || (batch.getResult_file() != null && batch.getResult_file().isEmpty())) {
            batch.setResult_file(createDetailsFile(transfers));
        }
        batch.setCompleted(completed);
        batch.setFailed(failed);
        batch.setResultGeneratedAt(new Date());
        batch.setOngoing(ongoing);
        batch.setTotalTransactions(total);
        batchRepository.save(batch);

        return new BatchDTO(batch.getBatchId(),
                batch.getRequestId(), batch.getTotalTransactions(), batch.getOngoing(),
                batch.getFailed(), batch.getCompleted(), totalAmount, completedAmount,
                ongoingAmount, failedAmount, batch.getResult_file(), batch.getResultGeneratedAt(), batch.getNote());
    }

    private String createDetailsFile(List<Transfer> transfers) {
        String CSV_SEPARATOR = ",";
        File tempFile = new File(System.currentTimeMillis() + "_response.csv");
        try (
                FileWriter writer = new FileWriter(tempFile.getName());
                BufferedWriter bw = new BufferedWriter(writer)) {
            for (Transfer transfer : transfers)
            {
                StringBuffer oneLine = new StringBuffer();
                oneLine.append(transfer.getTransactionId());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getStatus().toString());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getPayeeDfspId());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getPayeePartyId());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getPayerDfspId());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getPayerPartyId());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getAmount().toString());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getCurrency());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getErrorInformation());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getStartedAt().toString());
                oneLine.append(CSV_SEPARATOR);
                oneLine.append(transfer.getCompletedAt().toString());
                oneLine.append(CSV_SEPARATOR);
                bw.write(oneLine.toString());
                bw.newLine();
            }
            bw.flush();
            return fileTransferService.uploadFile(tempFile, bucketName);
        } catch (Exception e) {
            System.err.format("Exception: %s%n", e);
        }
        return null;
    }

}
