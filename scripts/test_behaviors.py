#!/usr/bin/env python3
"""State/invariant regression tests beyond the cross-language demo transcripts."""
import contextlib
import io
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'Python' / 'DesignPatterns'))
import patterns as p

class Behaviors(unittest.TestCase):
    def setUp(self):
        self.output = io.StringIO()
        self.redirect = contextlib.redirect_stdout(self.output)
        self.redirect.__enter__()
    def tearDown(self): self.redirect.__exit__(None, None, None)
    def test_atm_rejects_actions_while_dispensing(self):
        atm = p.ATM(); atm.insert(); atm.dispense()
        atm.insert(); atm.cancel(); atm.eject(); atm.dispense()
        self.assertIs(atm.state, atm.dispensing)
        atm.complete(); self.assertIs(atm.state, atm.no_card)
        atm.complete(); self.assertEqual(self.output.getvalue().count('Cash dispensed: 100'), 1)
    def test_atm_cancel_allows_new_transaction(self):
        atm = p.ATM(); atm.insert(); atm.cancel(); self.assertIs(atm.state, atm.no_card)
        atm.insert(); self.assertIs(atm.state, atm.has_card)
    def test_proxy_caches_empty_values_and_invalidates(self):
        backing = p.MemoryDatabase(); db = p.ProxyDatabase(backing)
        db.write('k', ''); self.assertEqual(db.read('k'), ''); self.assertEqual(db.read('k'), '')
        self.assertEqual(self.output.getvalue().count('DB read: k'), 1)
        db.write('k', 'updated'); self.assertEqual(db.read('k'), 'updated')
        self.assertIsNone(db.read('absent')); db.write('absent', 'now present'); self.assertEqual(db.read('absent'), 'now present')
    def test_observer_snapshot_and_unsubscribe(self):
        stock = p.Stock(); events = []
        class Subscriber:
            def notify(self, value): events.append(value); stock.remove(self)
        subscriber = Subscriber(); stock.add(subscriber); stock.add(subscriber)
        stock.set_price(10); stock.set_price(20); self.assertEqual(events, [10])
        with self.assertRaises(ValueError): stock.set_price(-1)
        self.assertEqual(stock.price, 20)
    def test_round_robin_wrap_and_least_connection_tie(self):
        servers = [p.Server('A', 0), p.Server('B', 0)]
        strategy = p.RoundRobin()
        self.assertEqual([strategy.select(servers).name for _ in range(5)], ['A','B','A','B','A'])
        lb = p.LoadBalancer(servers, p.LeastConnections())
        first = lb.send('one'); self.assertIs(first, servers[0]); self.assertIs(lb.send('two'), servers[1])
        lb.complete(first)
        with self.assertRaises(ValueError): lb.complete(first)
        with self.assertRaises(ValueError): p.LoadBalancer([], p.RoundRobin()).send('empty')
    def test_tree_rename_keeps_lookup_consistent(self):
        folder = p.Folder('root'); first, second = p.File('one'), p.File('two'); folder.add(first); folder.add(second)
        first.rename('renamed'); self.assertNotIn('one', folder.children); self.assertIs(folder.children['renamed'], first)
        first.rename('renamed')
        with self.assertRaises(ValueError): first.rename('two')
        self.assertIs(folder.children['renamed'], first); self.assertEqual(first.name, 'renamed')
    def test_tree_ownership_and_cycle(self):
        root, child = p.Folder('root'), p.Folder('child'); root.add(child)
        with self.assertRaises(ValueError): child.add(root)
        with self.assertRaises(ValueError): root.add(root)
        with self.assertRaises(ValueError): p.Folder('other').add(child)
        self.assertIs(child.parent, root); self.assertIsNone(root.parent)
    def test_file_overwrite_append_and_dynamic_size(self):
        root, file = p.Folder('root'), p.File('file'); root.add(file)
        file.append('hello'); file.modify('a', 1); self.assertEqual(file.content, 'hallo')
        file.modify('!', 5); self.assertEqual(file.content, 'hallo!'); self.assertEqual(root.size(), 6)
        file.modify('XYZ', 5); self.assertEqual(file.content, 'halloXYZ'); self.assertEqual(root.size(), 8)
        for offset in (-1, 100):
            with self.assertRaises(ValueError): file.modify('x', offset)
    def test_retry_stops_after_success(self):
        class Flaky(p.Notification):
            attempts = 0
            def send(self, message):
                self.attempts += 1
                if self.attempts < 2: raise RuntimeError('transient')
                self.message = message
        flaky = Flaky(); p.FormattingDecorator(p.RetryDecorator(flaky)).send(' hello ')
        self.assertEqual(flaky.attempts, 2); self.assertEqual(flaky.message, 'hello')
    def test_retry_propagates_terminal_failure(self):
        class Failed(p.Notification):
            attempts = 0
            def send(self, message): self.attempts += 1; raise RuntimeError('offline')
        failed = Failed()
        with self.assertRaisesRegex(RuntimeError, 'offline'): p.RetryDecorator(failed).send('hello')
        self.assertEqual(failed.attempts, 3)
    def test_builder_returns_independent_values(self):
        builder = p.StudentBuilder('Neha').set_age(22); first = builder.build(); second = builder.set_age(23).build()
        self.assertEqual(first.age, 22); self.assertEqual(second.age, 23)
    def test_factory_rejects_unknown_and_reuses_stateless_product(self):
        self.assertIs(p.NotificationFactory.get('sms'), p.NotificationFactory.get('SMS'))
        with self.assertRaises(ValueError): p.NotificationFactory.get('EMAIL')

if __name__ == '__main__': unittest.main()
