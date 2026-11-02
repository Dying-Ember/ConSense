"""Static fixture contract only; no Java, database, network or Docker execution."""
from pathlib import Path
import unittest


class TcpSyntheticClientSourceTest(unittest.TestCase):
    def test_fixed_endpoint_and_redacted_failures(self):
        source = Path(__file__).with_name('H2TcpSyntheticClient.java').read_text(encoding='utf-8')
        self.assertIn('jdbc:h2:tcp://127.0.0.1:28682/./fixture;IFEXISTS=TRUE', source)
        self.assertEqual(source.count('DriverManager.getConnection('), 1)
        self.assertNotIn('System.getenv(', source)
        self.assertNotIn('printStackTrace(', source)
        self.assertNotIn('getMessage(', source)
        self.assertNotIn('println(PASSWORD', source)

    def test_writer_commits_before_readback_and_reader_has_no_write_statement(self):
        source = Path(__file__).with_name('H2TcpSyntheticClient.java').read_text(encoding='utf-8')
        start = source.index('if ("write".equals(args[0]))')
        end = source.index('verifyMarker(c, 2, "tcp-synthetic-marker")', start)
        write_branch = source[start:end]
        self.assertIn("INSERT INTO probe_marker VALUES (2, 'tcp-synthetic-marker')", write_branch)
        self.assertIn('c.commit()', write_branch)
        self.assertEqual(source.count('executeUpdate('), 1)
        self.assertEqual(source.count('c.commit()'), 1)
        self.assertIn('failure may be ambiguous', write_branch)
        self.assertIn('SELECT marker FROM probe_marker WHERE id=?', source)


if __name__ == '__main__':
    unittest.main()
