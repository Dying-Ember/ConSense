"""Synthetic request deadlines with deliberately uncooperative workers."""
import asyncio
import threading
import time
from unittest.mock import patch

import source_request_context as source_context
import unittest

from source_request_context import execute_owned, publish
from token_windows import WindowError


class Request:
    def __init__(self, disconnected=None):
        self.disconnected = disconnected or threading.Event()

    async def is_disconnected(self):
        return self.disconnected.is_set()


class BoundedOwnedRequestTest(unittest.TestCase):
    def test_deadline_returns_while_worker_runs_and_blocks_later_publication(self):
        async def exercise():
            started = threading.Event()
            release = threading.Event()
            exited = threading.Event()
            writes = []
            def work():
                started.set()
                try:
                    release.wait(2)
                    publish(lambda: writes.append('published'))
                finally:
                    exited.set()
            task = asyncio.create_task(execute_owned(Request(), work, .1))
            try:
                self.assertTrue(await asyncio.to_thread(started.wait, 1))
                with self.assertRaises(WindowError) as caught:
                    await asyncio.wait_for(asyncio.shield(task), 1)
                self.assertEqual('SOURCE_REQUEST_TIMEOUT', caught.exception.kind)
                self.assertTrue(caught.exception.metadata.get('requestId'))
                self.assertIsNone(caught.exception.metadata.get('journalId'))
                self.assertFalse(exited.is_set())
                self.assertEqual([], writes)
            finally:
                release.set()
                self.assertTrue(await asyncio.to_thread(exited.wait, 1))
                if not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
            self.assertEqual([], writes)
        asyncio.run(exercise())

    def test_disconnect_returns_while_worker_remains_running(self):
        async def exercise():
            started = threading.Event()
            release = threading.Event()
            exited = threading.Event()
            disconnected = threading.Event()
            def work():
                started.set()
                try:
                    release.wait(2)
                finally:
                    exited.set()
            task = asyncio.create_task(execute_owned(Request(disconnected), work, 5))
            try:
                self.assertTrue(await asyncio.to_thread(started.wait, 1))
                disconnected.set()
                with self.assertRaises((WindowError, asyncio.CancelledError)):
                    await asyncio.wait_for(asyncio.shield(task), 1)
                self.assertFalse(exited.is_set())
            finally:
                release.set()
                self.assertTrue(await asyncio.to_thread(exited.wait, 1))
                if not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
        asyncio.run(exercise())

    def test_started_publication_can_finish_after_deadline_without_being_misreported(self):
        async def exercise():
            entered = threading.Event()
            release = threading.Event()
            exited = threading.Event()
            writes = []
            def action():
                entered.set()
                release.wait(2)
                writes.append('published')
            def work():
                try:
                    publish(action)
                finally:
                    exited.set()
            task = asyncio.create_task(execute_owned(Request(), work, .1))
            try:
                self.assertTrue(await asyncio.to_thread(entered.wait, 1))
                with self.assertRaises(WindowError) as caught:
                    await asyncio.wait_for(asyncio.shield(task), 1)
                self.assertEqual('SOURCE_REQUEST_TIMEOUT', caught.exception.kind)
                self.assertTrue(caught.exception.metadata.get('requestId'))
                self.assertFalse(exited.is_set())
                self.assertEqual([], writes)
            finally:
                release.set()
                self.assertTrue(await asyncio.to_thread(exited.wait, 1))
                if not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
            self.assertEqual(['published'], writes)
        asyncio.run(exercise())

    def test_timeout_snapshots_only_journal_identity_already_bound(self):
        async def exercise():
            entered, release = threading.Event(), threading.Event()
            def work():
                source_context.bind_journal('authored-journal', lambda intent: None)
                entered.set(); release.wait(2)
            try:
                with self.assertRaises(WindowError) as caught:
                    await execute_owned(Request(), work, .1)
                self.assertTrue(entered.is_set())
                self.assertEqual('authored-journal', caught.exception.metadata['journalId'])
                self.assertEqual('unknown', caught.exception.metadata['outcome'])
            finally:
                release.set()
        asyncio.run(exercise())

    def test_hanging_disconnect_probe_cannot_extend_deadline(self):
        class HangingRequest:
            async def is_disconnected(self):
                await asyncio.sleep(10)
                return False
        async def exercise():
            entered, release = threading.Event(), threading.Event()
            def work():
                entered.set(); release.wait(2)
            try:
                with self.assertRaises(WindowError) as caught:
                    await asyncio.wait_for(execute_owned(HangingRequest(), work, .1), 1)
                self.assertEqual('SOURCE_REQUEST_TIMEOUT', caught.exception.kind)
                self.assertEqual('unknown', caught.exception.metadata['outcome'])
                self.assertTrue(entered.is_set())
            finally:
                release.set()
        asyncio.run(exercise())

    def test_completed_result_wins_when_disconnect_and_completion_are_both_ready(self):
        async def exercise():
            signal = asyncio.Event()
            entered, release, finished = threading.Event(), threading.Event(), threading.Event()
            loop = asyncio.get_running_loop()
            class DisconnectingRequest:
                async def is_disconnected(self):
                    await signal.wait()
                    return True
            def work():
                entered.set()
                release.wait(1)
                loop.call_soon_threadsafe(signal.set)
                finished.set()
                return 'completed'
            task = asyncio.create_task(execute_owned(DisconnectingRequest(), work, 1))
            self.assertTrue(await asyncio.to_thread(entered.wait, 1))
            await asyncio.sleep(.01)
            release.set()
            self.assertTrue(finished.wait(1))
            time.sleep(.03)
            self.assertEqual('completed', await task)
        asyncio.run(exercise())

    def test_result_timestamp_decides_when_completion_and_deadline_are_both_ready(self):
        async def exercise(delay, expected):
            finished = threading.Event()
            def work():
                time.sleep(delay)
                finished.set()
                return 'done'
            task = asyncio.create_task(execute_owned(Request(), work, .15))
            await asyncio.sleep(.02)
            # Block delivery until both the completion and deadline have elapsed.
            self.assertTrue(finished.wait(1))
            time.sleep(.2)
            if expected == 'done':
                self.assertEqual('done', await task)
            else:
                with self.assertRaises(WindowError) as caught:
                    await task
                self.assertEqual('SOURCE_REQUEST_TIMEOUT', caught.exception.kind)
        asyncio.run(exercise(.01, 'done'))
        asyncio.run(exercise(.18, 'timeout'))


if __name__ == '__main__':
    unittest.main()
