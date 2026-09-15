-- Signal items belong to immutable terminal run results just like their parent signals.
CREATE TRIGGER prevent_completed_signal_items_insert
BEFORE INSERT ON backtest_signal_items
FOR EACH ROW
WHEN (SELECT r.status FROM backtest_runs r JOIN backtest_signals s ON s.run_id = r.id WHERE s.id = NEW.signal_id)
     IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert signal items for a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_signal_items_update
BEFORE UPDATE ON backtest_signal_items
FOR EACH ROW
WHEN (SELECT r.status FROM backtest_runs r JOIN backtest_signals s ON s.run_id = r.id WHERE s.id = OLD.signal_id)
     IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update signal items of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_signal_items_delete
BEFORE DELETE ON backtest_signal_items
FOR EACH ROW
WHEN (SELECT r.status FROM backtest_runs r JOIN backtest_signals s ON s.run_id = r.id WHERE s.id = OLD.signal_id)
     IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete signal items of a terminated backtest run');
END;
