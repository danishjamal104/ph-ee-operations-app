ALTER TABLE `batches`
    ADD COLUMN `PAYER_FSP` VARCHAR(255) DEFAULT NULL;

ALTER TABLE `batches`
    ADD COLUMN `REGISTERING_INSTITUTION_ID` VARCHAR(255) DEFAULT NULL;

ALTER TABLE `batches`
    ADD COLUMN `CLIENT_CORRELATION_ID` VARCHAR(255) DEFAULT NULL;