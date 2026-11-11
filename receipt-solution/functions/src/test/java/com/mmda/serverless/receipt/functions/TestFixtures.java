package com.mmda.serverless.receipt.functions;

final class TestFixtures {

    static final ReceiptConfig CONFIG = new ReceiptConfig() {
        @Override
        public String tableName() {
            return "receipts-table";
        }

        @Override
        public String eventBusName() {
            return "receipts-bus";
        }
    };

    private TestFixtures() {
    }
}
