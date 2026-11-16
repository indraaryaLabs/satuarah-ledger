package click.satuarah.ledger.store;

import click.satuarah.ledger.application.LedgerServiceContract;
import click.satuarah.ledger.application.LedgerStore;

class InMemoryLedgerServiceTest extends LedgerServiceContract {

    @Override
    protected LedgerStore newStore() {
        return new InMemoryLedgerStore();
    }
}
