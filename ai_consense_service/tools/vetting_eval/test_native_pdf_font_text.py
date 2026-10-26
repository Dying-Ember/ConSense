"""Original font-metadata controls, not PDF rendering or source-quality scores.

The tiny authored SFNT contains cmap/maxp metadata for deterministic negatives;
it is not a usable rendering font or a claim of a fresh Java observation.
"""
import copy
import io
import struct
import unittest

from native_pdf_observation import (FONT_TEXT_SCHEMA, FONT_TEXT_VERSION, SYMBOL_MAPPING_SOURCE,
    SYMBOL_TEXT, NativePdfObservationError, _source_symbol_fonts, _text_resolution, reader_glyph_text)
from native_pdf_canonical_projection import _native_text


def metadata_pdf(*, encoding='/Identity-H', cid_map='/Identity', wrong_unicode=False, wrong_cmap=False, glyph_count=101):
    from pypdf import PdfWriter
    from pypdf.generic import (DictionaryObject, NameObject, ArrayObject, DecodedStreamObject)
    # Format 4, three selectors plus sentinel, each using idDelta with no glyph array.
    codes, gids = [0xf03e, 0xf046, 0xf0a3, 0xffff], [33, 42 if wrong_cmap else 41, 100, 0]
    subtable = struct.pack('>7H', 4, 48, 0, 8, 0, 0, 0)
    subtable += struct.pack('>4H', *codes) + b'\0\0' + struct.pack('>4H', *codes)
    subtable += struct.pack('>4H', *[(gid-code) & 65535 for code,gid in zip(codes,gids)]) + b'\0'*8
    cmap = struct.pack('>HHHHI', 0, 1, 3, 0, 12) + subtable
    maxp=struct.pack('>IH',0x10000,glyph_count)
    program = struct.pack('>IHHHH', 0x10000, 2, 0, 0, 0)
    program += b'cmap' + struct.pack('>III', 0, 44, len(cmap))
    program += b'maxp' + struct.pack('>III', 0, 44+len(cmap), len(maxp)) + cmap + maxp
    def stream(raw):
        value = DecodedStreamObject(); value.set_data(raw); return value
    unicode = b'1 begincodespacerange\n<0000> <ffff>\nendcodespacerange\n3 beginbfchar\n<0021> <f03e>\n<0029> <'+(b'f03e' if wrong_unicode else b'f046')+b'>\n<0064> <f0a3>\nendbfchar'
    writer = PdfWriter(); page = writer.add_blank_page(100,100)
    descriptor = DictionaryObject({NameObject('/FontFile2'): writer._add_object(stream(program))})
    descendant = DictionaryObject({NameObject('/Subtype'): NameObject('/CIDFontType2'),
        NameObject('/CIDToGIDMap'): NameObject(cid_map), NameObject('/FontDescriptor'): writer._add_object(descriptor)})
    font = DictionaryObject({NameObject('/Subtype'): NameObject('/Type0'), NameObject('/BaseFont'): NameObject('/Symbol'),
        NameObject('/Encoding'): NameObject(encoding), NameObject('/DescendantFonts'): ArrayObject([writer._add_object(descendant)]),
        NameObject('/ToUnicode'): writer._add_object(stream(unicode))})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): writer._add_object(font)})})
    output = io.BytesIO(); writer.write(output); return output.getvalue()


def glyph(proof, raw='\uf046', code=41):
    receipt = {'schema':FONT_TEXT_SCHEMA, 'decoderVersion':FONT_TEXT_VERSION,
        'rawUnicode':raw, 'sourceCharacterCodes':[code], 'fontName':'Symbol', 'selector':format(ord(raw),'04X'),
        'decodedText':SYMBOL_TEXT.get(raw, ''), 'resolutionStatus':'resolved_embedded_symbol_selector',
        'mappingSource':SYMBOL_MAPPING_SOURCE, 'fontProgramSha256':proof['fontProgramSha256'],
        'toUnicodeSha256':proof['toUnicodeSha256'], 'sourceGlyphId':code, 'selectorGlyphId':code,
        'cmapPlatformId':3, 'cmapEncodingId':0}
    return {'id':'authored-glyph', 'unicode':raw, 'characterCodes':[code], 'fontName':'Symbol',
        'fontSubtype':'Type0', 'fontEmbedded':True, 'textResolution':receipt,
        'effectiveVisibilityStatus':'unknown_not_raster_verified', 'visibilityStatus':'operator_visible_unverified'}


class NativePdfFontTextTest(unittest.TestCase):
    def setUp(self):
        self.fonts = _source_symbol_fonts(metadata_pdf(), [1])[1]
        self.glyph = glyph(self.fonts['Symbol'][0])

    def check(self, value=None, fonts=None, **kwargs):
        _text_resolution(value or self.glyph, enabled=True, fonts=self.fonts if fonts is None else fonts, **kwargs)

    def test_actual_source_streams_and_cmap_prove_cid_not_selector_byte(self):
        for raw, code in [('\uf046',41), ('\uf03e',33), ('\uf0a3',100)]:
            value = glyph(self.fonts['Symbol'][0],raw,code); self.check(value)
            self.assertEqual(SYMBOL_TEXT[raw], reader_glyph_text(value,font_text=True))
            self.assertEqual(raw, reader_glyph_text(value))
        value = copy.deepcopy(self.glyph); value['characterCodes'] = [0x46]
        value['textResolution'].update(sourceCharacterCodes=[0x46],sourceGlyphId=0x46,selectorGlyphId=0x46)
        with self.assertRaises(NativePdfObservationError): self.check(value)

    def test_source_metadata_encoding_unicode_and_cmap_changes_reject_receipt(self):
        for kwargs in ({'encoding':'/Identity-V'}, {'cid_map':'/Other'}, {'wrong_unicode':True}, {'wrong_cmap':True}, {'glyph_count':41}):
            with self.subTest(kwargs=kwargs), self.assertRaises(NativePdfObservationError):
                self.check(fonts=_source_symbol_fonts(metadata_pdf(**kwargs),[1])[1])

    def test_receipt_identity_hash_gid_types_and_ambiguous_original_font_rejected(self):
        for key,value in [('fontProgramSha256','a'*64), ('toUnicodeSha256','b'*64), ('selector','F03E'),
                          ('rawUnicode','x'), ('fontName','OtherSymbol'), ('sourceGlyphId',33),
                          ('selectorGlyphId',33), ('cmapPlatformId',True), ('decodedText','φ'),
                          ('sourceCharacterCodes',[True]), ('extra','unsupported')]:
            changed = copy.deepcopy(self.glyph); changed['textResolution'][key] = value
            with self.subTest(field=key), self.assertRaises(NativePdfObservationError): self.check(changed)
        with self.assertRaises(NativePdfObservationError): self.check(fonts={'Symbol':[self.fonts['Symbol'][0],None]})
        with self.assertRaises(NativePdfObservationError): self.check(fonts={})

    def test_unresolved_mu_is_non_adopting_and_ap_nonprivate_legacy_are_raw(self):
        changed = glyph(self.fonts['Symbol'][0], '\uf06d',77)
        changed['textResolution'].update(decodedText='',mappingSource=None,resolutionStatus='unresolved_unsupported_selector')
        self.check(changed); self.assertEqual('\uf06d',reader_glyph_text(changed,font_text=True))
        changed['textResolution']['decodedText'] = 'μ'
        with self.assertRaises(NativePdfObservationError): self.check(changed)
        for kwargs in ({'enabled':False}, {'enabled':True,'appearance':True}):
            with self.assertRaises(NativePdfObservationError): _text_resolution(self.glyph,fonts=self.fonts,**kwargs)
        changed = copy.deepcopy(self.glyph); changed['unicode']='x'
        with self.assertRaises(NativePdfObservationError): self.check(changed)

    def test_derived_region_preserves_original_utf16_and_receipt_and_old_recipe(self):
        self.check()
        row={'glyphIdsInReadingOrder':['authored-glyph'],'capturedGlyphIds':['authored-glyph'],'separators':[]}
        original_span={'glyphId':'authored-glyph','start':7,'end':8}
        members={'authored-glyph':(self.glyph,original_span,'/actual/raw/glyph/0')}
        raw,raw_spans = _native_text(row,members)
        derived,spans = _native_text(row,members,font_text=True)
        self.assertEqual('\uf046',raw); self.assertEqual('Φ',derived)
        self.assertEqual(original_span,spans[0]['originalUtf16Span'])
        self.assertEqual(self.glyph['textResolution'],spans[0]['textResolution'])
        self.assertNotIn('textResolution',raw_spans[0]); self.assertEqual('\uf046',self.glyph['unicode'])


if __name__ == '__main__': unittest.main()
