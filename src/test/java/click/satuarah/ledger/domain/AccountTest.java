package click.satuarah.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static Account account(AccountType type, boolean allowNegative, long balance) {
        return new Account("X", "x", type, "IDR", allowNegative, balance, Instant.EPOCH);
    }

    @Test
    void debitNormalAccountsReadPositiveWhenDebited() {
        assertEquals(500, account(AccountType.ASSET, false, 500).naturalBalance());
        assertEquals(500, account(AccountType.EXPENSE, false, 500).naturalBalance());
    }

    @Test
    void creditNormalAccountsReadPositiveWhenCredited() {
        assertEquals(500, account(AccountType.LIABILITY, false, -500).naturalBalance());
        assertEquals(500, account(AccountType.REVENUE, false, -500).naturalBalance());
        assertEquals(500, account(AccountType.EQUITY, false, -500).naturalBalance());
    }

    @Test
    void anAssetIsOverdrawnBelowZeroAndALiabilityAboveZero() {
        assertTrue(account(AccountType.ASSET, false, 0).overdrawnAt(-1));
        assertFalse(account(AccountType.ASSET, false, 0).overdrawnAt(0));
        assertTrue(account(AccountType.LIABILITY, false, 0).overdrawnAt(1));
        assertFalse(account(AccountType.LIABILITY, false, 0).overdrawnAt(-1));
    }

    @Test
    void anAccountThatAllowsNegativeIsNeverOverdrawn() {
        assertFalse(account(AccountType.ASSET, true, 0).overdrawnAt(-1_000_000));
        assertFalse(account(AccountType.LIABILITY, true, 0).overdrawnAt(1_000_000));
    }

    @Test
    void withBalanceChangesOnlyTheBalance() {
        Account changed = account(AccountType.ASSET, false, 1).withBalance(9);
        assertEquals(9, changed.balance());
        assertEquals("X", changed.code());
    }

    @Test
    void sidesKnowTheirOppositeAndSign() {
        assertEquals(Side.CREDIT, Side.DEBIT.opposite());
        assertEquals(Side.DEBIT, Side.CREDIT.opposite());
        assertEquals(7, Side.DEBIT.signed(7));
        assertEquals(-7, Side.CREDIT.signed(7));
        assertEquals(Side.DEBIT, AccountType.ASSET.normalSide());
        assertEquals(Side.CREDIT, AccountType.REVENUE.normalSide());
    }

    @Test
    void accountCodesAreShortAndSafe() {
        assertTrue(Account.validCode("1100-BANK"));
        assertTrue(Account.validCode("a"));
        assertFalse(Account.validCode(""));
        assertFalse(Account.validCode("-lead"));
        assertFalse(Account.validCode("has space"));
        assertFalse(Account.validCode("x".repeat(33)));
        assertFalse(Account.validCode(null));
    }

    @Test
    void aNewAccountIsValidated() {
        assertEquals("IDR", new NewAccount("BANK", "Bank", AccountType.ASSET, "IDR", false).currency());
        assertInvalid(() -> new NewAccount("bad code", "Bank", AccountType.ASSET, "IDR", false));
        assertInvalid(() -> new NewAccount("BANK", " ", AccountType.ASSET, "IDR", false));
        assertInvalid(() -> new NewAccount("BANK", "x".repeat(121), AccountType.ASSET, "IDR", false));
        assertInvalid(() -> new NewAccount("BANK", "Bank", null, "IDR", false));
        assertInvalid(() -> new NewAccount("BANK", "Bank", AccountType.ASSET, "idr", false));
        assertInvalid(() -> new NewAccount("BANK", "Bank", AccountType.ASSET, null, false));
        assertInvalid(() -> new NewAccount("BANK", "Bank", AccountType.ASSET, "ZZZ", false));
    }

    private static void assertInvalid(Runnable action) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(LedgerException.class, action::run).code());
    }
}
