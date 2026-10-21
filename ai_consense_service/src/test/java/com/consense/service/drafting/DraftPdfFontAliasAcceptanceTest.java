package com.consense.service.drafting;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual installed-family alias acceptance, using the existing explicit renderer acceptance setting; launches no process. */
class DraftPdfFontAliasAcceptanceTest {
    @TempDir Path temporary;
    private DraftPdfRenderProfile profile(){
        String executable=System.getProperty("consense.acceptance.rendererExecutable");
        assumeTrue(executable!=null&&!executable.isEmpty(),"Explicit actual renderer acceptance configuration is required");
        return DraftPdfRenderProfile.libreOffice(Paths.get(executable),temporary.resolve("not-started"),1000);
    }
    private static boolean contains(Collection<String> values,String expected){return values.stream().anyMatch(value->value.equalsIgnoreCase(expected));}
    private static Set<String> families(Locale locale){return new TreeSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(locale)));}
    private static byte[] source(String... names)throws Exception {
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            for(String name:names){XWPFRun run=document.createParagraph().createRun();run.setFontFamily(name,XWPFRun.FontCharRange.eastAsia);run.setText("中文來源");}
            document.write(output);return output.toByteArray();
        }
    }
    @Test void installedYaHeiRootAndEnglishFamilyNamesBothSatisfyTheActualSourceGuard()throws Exception {
        DraftPdfRenderProfile profile=profile();
        Set<String> root=families(Locale.ROOT),english=families(Locale.ENGLISH);assertTrue(contains(root,"微软雅黑"),"This explicit machine acceptance requires the actual localized installed family");assertTrue(contains(english,"Microsoft YaHei"),"The English name must come from actual installed-font metadata");
        assertTrue(contains(profile.getInstalledFonts(),"微软雅黑"));assertTrue(contains(profile.getInstalledFonts(),"Microsoft YaHei"),"An installed face cannot be rejected merely because ROOT exposes its localized name");
        byte[] bytes=source("Microsoft YaHei","微软雅黑"),before=bytes.clone();Map<String,Object> result=DraftPdfSourceCapabilities.inspect(bytes,profile.getInstalledFonts());assertEquals("AVAILABLE",result.get("activeFamilyAvailability"));assertEquals(new TreeSet<>(Arrays.asList("Microsoft YaHei","微软雅黑")),result.get("activeFamilies"));assertArrayEquals(before,bytes);assertFalse(Files.exists(temporary.resolve("not-started")));
    }
    @Test void theMeasuredInventoryIsExactlyTheUnionOfActualRootAndEnglishFamiliesIncludingSimSun()throws Exception {
        DraftPdfRenderProfile profile=profile();
        Set<String> root=families(Locale.ROOT),english=families(Locale.ENGLISH);assertTrue(contains(root,"宋体"));assertTrue(contains(english,"SimSun"));Set<String> expected=new TreeSet<>(root);expected.addAll(english);
        assertEquals(expected,profile.getInstalledFonts(),"Only metadata-declared installed names enter the profile");
        Map<String,Object> result=DraftPdfSourceCapabilities.inspect(source("SimSun","宋体"),profile.getInstalledFonts());assertEquals(new TreeSet<>(Arrays.asList("SimSun","宋体")),result.get("activeFamilies"));
    }
    @Test void actualAliasInventoryStillRejectsMissingSourceFontsAndExtBDoesNotBecomeTheRegularFamily()throws Exception {
        DraftPdfRenderProfile profile=profile();String missing="Definitely Missing Source Font For Alias Regression";assertFalse(contains(profile.getInstalledFonts(),missing));
        DraftPdfConversionException missingFailure=assertThrows(DraftPdfConversionException.class,()->DraftPdfSourceCapabilities.inspect(source(missing),profile.getInstalledFonts()));assertEquals(DraftPdfConversionException.Code.FONT_CAPABILITY_MISSING,missingFailure.getCode());
        Set<String> extB=new TreeSet<>();for(String family:profile.getInstalledFonts())if(family.toLowerCase(Locale.ROOT).endsWith("-extb"))extB.add(family);assertTrue(contains(extB,"PMingLiU-ExtB"),"This machine control requires the actual installed ExtB face");
        for(String regular:Arrays.asList("PMingLiU","新細明體")){
            DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->DraftPdfSourceCapabilities.inspect(source(regular),extB));assertEquals(DraftPdfConversionException.Code.FONT_CAPABILITY_MISSING,failure.getCode());assertTrue(failure.getMessage().contains(regular));
        }
    }
}
