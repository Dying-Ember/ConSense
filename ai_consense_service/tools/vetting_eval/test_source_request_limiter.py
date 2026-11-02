import asyncio
import threading
import time
import unittest
import anyio
from unittest.mock import patch
from starlette.concurrency import run_in_threadpool
from source_request_context import execute_owned
from token_windows import WindowError

class Request:
    async def is_disconnected(self):
        return False

class LimiterCandidateTest(unittest.TestCase):
    def test_shared_limiter_other_work_and_queued_timeout(self):
        async def run():
            limiter=anyio.to_thread.current_default_thread_limiter(); limiter.total_tokens=1
            started, release, called=threading.Event(),threading.Event(),threading.Event()
            def other(): started.set();release.wait(2)
            task=asyncio.create_task(run_in_threadpool(other))
            self.assertTrue(await asyncio.to_thread(started.wait,1))
            try:
                with self.assertRaises(WindowError) as caught:
                    await execute_owned(Request(),lambda:called.set(),.1)
                self.assertEqual('SOURCE_REQUEST_TIMEOUT',caught.exception.kind)
                self.assertEqual('not_started',caught.exception.metadata['outcome'])
                self.assertFalse(called.is_set())
                self.assertEqual(1,limiter.borrowed_tokens)
            finally:
                release.set(); await task
            await asyncio.sleep(.05)
            self.assertEqual(0,limiter.borrowed_tokens)
            self.assertFalse(called.is_set())
        asyncio.run(run())

    def test_cancel_during_fast_acquire_does_not_leak_other_borrower_token(self):
        async def run():
            from anyio._backends._asyncio import AsyncIOBackend
            limiter=anyio.to_thread.current_default_thread_limiter();limiter.total_tokens=2
            holder=object();limiter.acquire_on_behalf_of_nowait(holder)
            entered=asyncio.Event();worker_called=threading.Event()
            async def checkpoint():
                entered.set()
                await asyncio.sleep(10)
            with patch.object(AsyncIOBackend,'cancel_shielded_checkpoint',new=checkpoint):
                request_task=asyncio.create_task(execute_owned(Request(),worker_called.set,1))
                await asyncio.wait_for(entered.wait(),1)
                self.assertEqual(2,limiter.borrowed_tokens)
                request_task.cancel()
                with self.assertRaises(asyncio.CancelledError): await request_task
                await asyncio.sleep(.05)
            self.assertEqual(1,limiter.borrowed_tokens)
            self.assertIn(holder,limiter.statistics().borrowers)
            limiter.release_on_behalf_of(holder)
            self.assertFalse(worker_called.is_set())
        asyncio.run(run())

    def test_running_timeout_retains_shared_token_until_thread_exits(self):
        async def run():
            limiter=anyio.to_thread.current_default_thread_limiter(); limiter.total_tokens=1
            started,release,other=threading.Event(),threading.Event(),threading.Event()
            def work(): started.set();release.wait(2)
            try:
                with self.assertRaises(WindowError) as caught:
                    await execute_owned(Request(),work,.1)
                self.assertEqual('unknown',caught.exception.metadata['outcome'])
                self.assertTrue(started.is_set()); self.assertEqual(1,limiter.borrowed_tokens)
                pending=asyncio.create_task(run_in_threadpool(lambda:other.set()))
                await asyncio.sleep(.05)
                self.assertFalse(other.is_set()); self.assertEqual(1,limiter.borrowed_tokens)
            finally:
                release.set()
            await asyncio.wait_for(pending,1)
            self.assertTrue(other.is_set())
            self.assertEqual(0,limiter.borrowed_tokens)
        asyncio.run(run())

    def test_cross_loop_alive_worker_blocks_until_exit(self):
        started,release,exited=threading.Event(),threading.Event(),threading.Event()
        def work():
            started.set();release.wait(2);exited.set()
        async def first():
            limiter=anyio.to_thread.current_default_thread_limiter();limiter.total_tokens=1
            with self.assertRaises(WindowError): await execute_owned(Request(),work,.1)
        async def second():
            limiter=anyio.to_thread.current_default_thread_limiter();limiter.total_tokens=1
            with self.assertRaises(WindowError) as caught: await execute_owned(Request(),lambda:None,.5)
            self.assertEqual('SOURCE_REQUEST_CAPACITY',caught.exception.kind)
            self.assertEqual(0,limiter.borrowed_tokens)
        try:
            asyncio.run(first()); self.assertTrue(started.is_set());self.assertFalse(exited.is_set())
            asyncio.run(second())
        finally:
            release.set();self.assertTrue(exited.wait(1))
        async def third():
            self.assertEqual('ok',await execute_owned(Request(),lambda:'ok',1))
        self.assertEqual(None,asyncio.run(third()))

    def test_deadline_and_slot_release_race_does_not_start_handler(self):
        async def run():
            limiter=anyio.to_thread.current_default_thread_limiter();limiter.total_tokens=1
            holder=object();limiter.acquire_on_behalf_of_nowait(holder)
            called=threading.Event()
            task=asyncio.create_task(execute_owned(Request(),lambda:called.set(),.1))
            await asyncio.sleep(.02)
            time.sleep(.15)
            limiter.release_on_behalf_of(holder)
            with self.assertRaises(WindowError) as caught: await task
            self.assertEqual('SOURCE_REQUEST_TIMEOUT',caught.exception.kind)
            self.assertEqual('not_started',caught.exception.metadata['outcome'])
            await asyncio.sleep(.05)
            self.assertFalse(called.is_set());self.assertEqual(0,limiter.borrowed_tokens)
        asyncio.run(run())

if __name__ == '__main__': unittest.main()
