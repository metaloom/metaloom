package io.metaloom.loom.db.transaction;

import java.util.function.Consumer;

public interface TransactionCallable extends Consumer<Transaction> {

}
