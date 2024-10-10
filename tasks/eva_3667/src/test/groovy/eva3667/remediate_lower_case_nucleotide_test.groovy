package eva3667

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import org.bson.Document
import org.junit.jupiter.api.Test
import org.opencb.commons.utils.CryptoUtils
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import uk.ac.ebi.eva.commons.models.data.Variant
import uk.ac.ebi.eva.commons.models.mongo.entity.VariantDocument
import uk.ac.ebi.eva.commons.models.mongo.entity.subdocuments.VariantStatsMongo

import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors

import static org.junit.jupiter.api.Assertions.*

class RemediationApplicationIntegrationTest {
    private static String LOWERCASE_LARGE_REF = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private static String LOWERCASE_LARGE_ALT = "gggggggggggggggggggggggggggggggggggggggggggggggggg"
    private static String UPPERCASE_LARGE_REF = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private static String UPPERCASE_LARGE_ALT = "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG"

    def setUpEnvAndRunRemediationWithQC(String testDBName, List<Document> filesData, List<Document> variantsData,
                                        List<Document> annotationsData, def qcMethod) {
        String resourceDir = "src/test/resources"
        String testPropertiesFile = resourceDir + "/application-test.properties"
        String workingDir = resourceDir + "/test_run"
        File nmcFile = new File(Paths.get(workingDir, "/non_merged_candidates/", testDBName + ".txt").toString())

        // Removing existing data and setup DB with test Data
        AnnotationConfigApplicationContext context = getApplicationContext(testPropertiesFile, testDBName)
        MongoTemplate mongoTemplate = context.getBean(MongoTemplate.class)
        MappingMongoConverter converter = mongoTemplate.getConverter()
        converter.setTypeMapper(new DefaultMongoTypeMapper(null))

        mongoTemplate.getCollection(RemediationApplication.FILES_COLLECTION).drop()
        mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION).drop()
        mongoTemplate.getCollection(RemediationApplication.ANNOTATIONS_COLLECTION).drop()
        if (nmcFile.exists()) {
            nmcFile.delete()
        }
        if (filesData != null && !filesData.isEmpty()) {
            mongoTemplate.getCollection(RemediationApplication.FILES_COLLECTION).insertMany(filesData)
        }
        if (variantsData != null && !variantsData.isEmpty()) {
            mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION).insertMany(variantsData)
        }
        if (annotationsData != null && !annotationsData.isEmpty()) {
            mongoTemplate.getCollection(RemediationApplication.ANNOTATIONS_COLLECTION).insertMany(annotationsData)
        }


        // Run Remediation
        RemediationApplication remediationApplication = context.getBean(RemediationApplication.class)
        remediationApplication.run(new String[]{workingDir, testDBName})

        // Run QC
        qcMethod.call(mongoTemplate, workingDir, testDBName)

        context.close()
    }

    private AnnotationConfigApplicationContext getApplicationContext(String testPropertiesFile, String testDBName) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()
        def appProps = new Properties()
        appProps.load(new FileInputStream(new File(testPropertiesFile)))
        Map<String, Object> otherProperties = new HashMap<>()
        otherProperties.put("parameters.path", testPropertiesFile)
        otherProperties.put("spring.data.mongodb.database", testDBName)
        context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("main", appProps))
        if (Objects.nonNull(otherProperties)) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("other", otherProperties))
        }
        context.register(RemediationApplication.class)
        context.refresh()

        return context
    }

    @Test
    void testRemediateLowerCaseNucleotideWithDifferentCases() {
        String testDBName = "test_lowercase_remediation_db_different_cases"
        List<Document> filesData = getFilesDataForDifferentTestCases()
        List<Document> variantsData = getVariantsDataForDifferentTestCases()
        List<Document> annotationsData = getAnnotationsDataForDifferentTestCases()
        setUpEnvAndRunRemediationWithQC(testDBName, filesData, variantsData, annotationsData, this.&qcTestWithDifferentCases)
    }

    @Test
    void testRemediateLowerCaseNucleotideHgvsNotPresent() {
        String testDBName = "test_lowercase_remediation_db_hgvs_not_present"
        List<Document> variantsData = getVariantsDataForHgvsNotPresent()
        setUpEnvAndRunRemediationWithQC(testDBName, new ArrayList<>(), variantsData, new ArrayList<>(), this.&qcTestNoHgvsPresent)
    }

    @Test
    void testRemediateLowerCaseNucleotideForLargeRefAndAlt() {
        String testDBName = "test_lowercase_remediation_db_large_ref_alt"
        List<Document> filesData = getFilesDataForDifferentTestCases()
        List<Document> variantsData = getVariantsDataForLargeRefAlt()
        List<Document> annotationsData = getAnnotationsDataForLargeRefAlt()
        setUpEnvAndRunRemediationWithQC(testDBName, filesData, variantsData, annotationsData, this.&qcTestWithDifferentCasesLargeRefAlt)
    }

    @Test
    void testRemediateLowerCaseNucleotideCalculateStats() {
        String testDBName = "test_lowercase_remediation_db_stat_calculation"
        List<Document> filesData = getFilesDataForStatsCalculation()
        List<Document> variantsData = getVariantsDataForStatsCalculation()
        setUpEnvAndRunRemediationWithQC(testDBName, filesData, variantsData, new ArrayList<>(), this.&qcTestWithStatsCalculation)
    }

    @Test
    void testRegex() {
        Pattern pattern = Pattern.compile(RemediationApplication.REGEX_PATTERN)
        String[] matchingStrings = new String[]{
                buildVariantId("chr1", 77777777, "A", "g"),
                buildVariantId("chr1", 77777777, "a", "G"),
                buildVariantId("chr1", 77777777, "a", "g"),
                buildVariantId("chr1", 77777777, "a", ""),
                buildVariantId("chr1", 77777777, "", "g"),
                buildVariantId("chr1", 77777777, "AcG", "AgT"),
                buildVariantId("chr1", 77777777, "", "AgT"),
                buildVariantId("chr1", 77777777, "AcG", ""),
                buildVariantId("chr1", 77777777, "cAG", "gAT"),
                buildVariantId("chr1", 77777777, "AGc", "ATg"),
                buildVariantId("chr1", 77777777, "AG5", "AT5"),
                buildVariantId("chr1", 77777777, "555", "555"),
                buildVariantId("chr1", 77777777, "a", "g"),
                buildVariantId("chr1", 77777777, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT),
                buildVariantId("chr1", 77777777, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT),
                buildVariantId("chr_1", 77777777, "A", "g"),
                buildVariantId("chr_1", 77777777, "a", "G"),
                buildVariantId("chr_1", 77777777, "a", "g"),
                buildVariantId("chr_1", 77777777, "a", ""),
                buildVariantId("chr_1", 77777777, "", "g"),
                buildVariantId("chr_1", 77777777, "AcG", "AgT"),
                buildVariantId("chr_1", 77777777, "", "AgT"),
                buildVariantId("chr_1", 77777777, "AcG", ""),
                buildVariantId("chr_1", 77777777, "cAG", "gAT"),
                buildVariantId("chr_1", 77777777, "AGc", "ATg"),
                buildVariantId("chr_1", 77777777, "AG5", "AT5"),
                buildVariantId("chr_1", 77777777, "555", "555"),
                buildVariantId("chr_1", 77777777, "a", "g"),
                buildVariantId("chr_1", 77777777, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT),
                buildVariantId("chr_1", 77777777, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT)
        }
        for (String str : matchingStrings) {
            Matcher matcher = pattern.matcher(str)
            assertTrue(matcher.matches(), "Expected string to match: " + str)
        }

        String[] notMatchingStrings = new String[]{
                buildVariantId("chr1", 77777777, "A", "G"),
                buildVariantId("chr1", 77777777, "A", ""),
                buildVariantId("chr1", 77777777, "", "G"),
                buildVariantId("chr1", 77777777, "ACT", "CTG"),
                buildVariantId("chr_1", 77777777, "A", "G"),
                buildVariantId("chr_1", 77777777, "A", ""),
                buildVariantId("chr_1", 77777777, "", "G"),
                buildVariantId("chr_1", 77777777, "ACT", "CTG")
        }
        for (String str : notMatchingStrings) {
            Matcher matcher = pattern.matcher(str)
            assertFalse(matcher.matches(), "Expected string not to match: " + str)
        }
    }

    List<Document> getFilesDataForDifferentTestCases() {
        return Arrays.asList(
                getFileDocument("sid21", "fid21", "file_name_21.vcf.gz")
                        .append("samp", new Document("samp211", 0).append("samp212", 1).append("samp213", 2)),
                getFileDocument("sid211", "fid211", "file_name_211.vcf.gz")
                        .append("samp", new Document("samp2111", 0).append("samp2112", 1).append("samp2113", 2)),
                getFileDocument("sid22", "fid22", "file_name_22.vcf.gz")
                        .append("samp", new Document("samp221", 0).append("samp222", 1).append("samp223", 2)),
                getFileDocument("sid222", "fid222", "file_name_222.vcf.gz")
                        .append("samp", new Document("samp2221", 0).append("samp2222", 1).append("samp2223", 2)),
                // case id collision - fid has more than one file
                getFileDocument("sid31", "fid31", "file_name_31.vcf.gz")
                        .append("samp", new Document("samp311", 0).append("samp312", 1).append("samp313", 2)),
                getFileDocument("sid31", "fid31", "file_name_31_1.vcf.gz")
                        .append("samp", new Document("samp311", 0).append("samp312", 1).append("samp313", 2)),
                //case id collision - fid has just one file
                getFileDocument("sid41", "fid41", "file_name_41.vcf.gz")
                        .append("samp", new Document("samp411", 0).append("samp412", 1).append("samp413", 2)),
                getFileDocument("sid411", "fid411", "file_name_411.vcf.gz")
                        .append("samp", new Document("samp4111", 0).append("samp4112", 1).append("samp4113", 2)),
                getFileDocument("sid42", "fid42", "file_name_42.vcf.gz")
                        .append("samp", new Document("samp421", 0).append("samp422", 1).append("samp423", 2)),
                getFileDocument("sid422", "fid422", "file_name_422.vcf.gz")
                        .append("samp", new Document("samp4221", 0).append("samp4222", 1).append("samp4223", 2))
        )
    }

    List<Document> getAnnotationsDataForDifferentTestCases() {
        return Arrays.asList(
                // delete lowercase and insert with uppercase
                new Document("_id", "chr1_11111111_a_g_82_82").append("cachev", 82).append("vepv", 82),
                // delete lowercase - uppercase already present
                new Document("_id", "chr1_11111111_a_g_83_83").append("cachev", 83).append("vepv", 83),
                new Document("_id", "chr1_11111111_A_G_83_83").append("cachev", 83).append("vepv", 83),
        )
    }

    List<Document> getAnnotationsDataForLargeRefAlt() {
        return Arrays.asList(
                // delete lowercase and insert with uppercase
                new Document("_id", buildVariantId("chr1", 11111111,
                        LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT) + "_82_82")
                        .append("cachev", 82).append("vepv", 82),
                // delete lowercase - uppercase already present
                new Document("_id", buildVariantId("chr1", 11111111,
                        LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT) + "_83_83")
                        .append("cachev", 83).append("vepv", 83),
                new Document("_id", buildVariantId("chr1", 11111111,
                        UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT) + "_83_83")
                        .append("cachev", 83).append("vepv", 83),
        )
    }

    List<Document> getVariantsDataForDifferentTestCases() {
        List<Document> variantDocumentList = new ArrayList<>()

        // case no id collision after remediation
        List<Document> hgvs11 = Arrays.asList(new Document("type", "genomic").append("name", "chr1:g.11111111a>g"))
        List<Document> files11 = Arrays.asList(new Document("sid", "sid11").append("fid", "fid11"))
        List<Document> stats11 = Arrays.asList(getVariantStats("sid11", "fid11", 0.11, 0.11, "a", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr1", "a", "g",
                11111111, 11111111, 1, hgvs11, files11, stats11))

        // case id collision - all sid and fid are different
        // variant with uppercase ref and alt
        List<Document> hgvs21 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222A>G"))
        List<Document> files21 = Arrays.asList(new Document("sid", "sid21").append("fid", "fid21").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid211").append("fid", "fid211").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats21 = Arrays.asList(getVariantStats("sid21", "fid21", 0.21, 0.21, "A", "0/0"),
                getVariantStats("sid211", "fid211", 0.211, 0.211, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr2", "A", "G",
                22222222, 22222222, 1, hgvs21, files21, stats21))

        // variant with lowercase ref and alt
        List<Document> hgvs22 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222a>g"))
        List<Document> files22 = Arrays.asList(new Document("sid", "sid22").append("fid", "fid22").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid222").append("fid", "fid222").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats22 = Arrays.asList(getVariantStats("sid22", "fid22", 0.22, 0.22, "a", "0/0"),
                getVariantStats("sid222", "fid222", 0.222, 0.222, "a", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr2", "a", "g",
                22222222, 22222222, 1, hgvs22, files22, stats22))


        // case id collision - fid has more than one file
        // variant with uppercase ref and alt
        List<Document> hgvs31 = Arrays.asList(new Document("type", "genomic").append("name", "chr3:g.33333333A>G"))
        List<Document> files31 = Arrays.asList(new Document("sid", "sid31").append("fid", "fid31"),
                new Document("sid", "sid311").append("fid", "fid311"))
        List<Document> stats31 = Arrays.asList(getVariantStats("sid31", "fid31", 0.31, 0.31, "A", "0/0"),
                getVariantStats("sid311", "fid311", 0.311, 0.311, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr3", "A", "G",
                33333333, 33333333, 1, hgvs31, files31, stats31))

        // variant with lowercase ref and alt
        List<Document> hgvs32 = Arrays.asList(new Document("type", "genomic").append("name", "chr3:g.33333333a>g"))
        List<Document> files32 = Arrays.asList(new Document("sid", "sid32").append("fid", "fid32"),
                new Document("sid", "sid322").append("fid", "fid322"),
                new Document("sid", "sid31").append("fid", "fid31"))
        List<Document> stats32 = Arrays.asList(getVariantStats("sid32", "fid32", 0.32, 0.32, "a", "0/0"),
                getVariantStats("sid322", "fid322", 0.322, 0.322, "a", "0/0"),
                getVariantStats("sid31", "fid31", 0.31, 0.31, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr3", "a", "g",
                33333333, 33333333, 1, hgvs32, files32, stats32))


        // case id collision - common fid has just one file
        // variant with uppercase ref and alt
        List<Document> hgvs41 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444A>G"))
        List<Document> files41 = Arrays.asList(new Document("sid", "sid41").append("fid", "fid41").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid411").append("fid", "fid411").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats41 = Arrays.asList(getVariantStats("sid41", "fid41", 0.41, 0.41, "A", "0/0"),
                getVariantStats("sid411", "fid411", 0.411, 0.411, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr4", "A", "G",
                44444444, 44444444, 1, hgvs41, files41, stats41))

        // variant with lowercase ref and alt
        List<Document> hgvs42 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444a>g"))
        List<Document> files42 = Arrays.asList(new Document("sid", "sid42").append("fid", "fid42").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid422").append("fid", "fid422").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid411").append("fid", "fid411").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats42 = Arrays.asList(getVariantStats("sid42", "fid42", 0.42, 0.42, "a", "0/0"),
                getVariantStats("sid422", "fid422", 0.422, 0.422, "a", "0/0"),
                getVariantStats("sid411", "fid411", 0.411, 0.411, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr4", "a", "g",
                44444444, 44444444, 1, hgvs42, files42, stats42))


        return variantDocumentList
    }

    void qcTestWithDifferentCases(MongoTemplate mongoTemplate, String workingDir, String dbName) {
        MongoCollection<VariantDocument> variantsColl = mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION)
        MongoCollection<Document> annotationsColl = mongoTemplate.getCollection(RemediationApplication.ANNOTATIONS_COLLECTION)
        // case_no_id_collision

        // assert lowercase variant deleted
        List<Document> lowerCaseVariantList = variantsColl.find(Filters.eq("_id", "chr1_11111111_a_g")).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        List<Document> upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr1_11111111_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())
        // assert all things updated to uppercase in the updated variant
        Document upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals('A', upperCaseVariant.get("ref"))
        assertEquals('G', upperCaseVariant.get("alt"))
        assertEquals('chr1:g.11111111A>G', upperCaseVariant.get("hgvs")[0]["name"])
        assertEquals('A', upperCaseVariant.get("st")[0]["mafAl"])

        // assert annotation remediation
        List<Document> lowercaseAnnot1 = annotationsColl.find(Filters.eq("_id", "chr1_11111111_a_g_82_82")).into([])
        List<Document> lowercaseAnnot2 = annotationsColl.find(Filters.eq("_id", "chr1_11111111_a_g_83_83")).into([])
        assertEquals(0, lowercaseAnnot1.size())
        assertEquals(0, lowercaseAnnot2.size())
        List<Document> uppercaseAnnot1 = annotationsColl.find(Filters.eq("_id", "chr1_11111111_A_G_82_82")).into([])
        List<Document> uppercaseAnnot2 = annotationsColl.find(Filters.eq("_id", "chr1_11111111_A_G_83_83")).into([])
        assertEquals(1, uppercaseAnnot1.size())
        assertEquals(1, uppercaseAnnot2.size())


        // case_id_collision_all_sid_fid_diff

        //  assert lowercase variant deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id", "chr2_22222222_a_g")).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr2_22222222_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals('A', upperCaseVariant.get("ref"))
        assertEquals('G', upperCaseVariant.get("alt"))
        assertEquals('chr2:g.22222222A>G', upperCaseVariant.get("hgvs")[0]["name"])
        assertEquals(Arrays.asList("sid21", "sid211", "sid22", "sid222"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid21", "fid211", "fid22", "fid222"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("sid21", "sid211", "sid22", "sid222"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid21", "fid211", "fid22", "fid222"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("G", "G", "G", "G"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("mafAl"))
                        .sorted().collect(Collectors.toList()))


        // case_id_collision_sid_fid_has_more_than_one_file

        // assert lowercase variant is not deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id", "chr3_33333333_a_g")).into([])
        assertEquals(1, lowerCaseVariantList.size())
        upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr3_33333333_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert the issue is logged in the file
        File nonMergedVariantFile = new File(Paths.get(workingDir, "non_merged_candidates", dbName + ".txt").toString())
        assertTrue(nonMergedVariantFile.exists())
        try (BufferedReader nmcFileReader = new BufferedReader(new FileReader(nonMergedVariantFile))) {
            assertEquals("sid31,fid31,chr3_33333333_a_g", nmcFileReader.readLine())
        }


        // case id collision - common fid has just one file

        //  assert lowercase variant deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id", "chr4_44444444_a_g")).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr4_44444444_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals('A', upperCaseVariant.get("ref"))
        assertEquals('G', upperCaseVariant.get("alt"))
        assertEquals('chr4:g.44444444A>G', upperCaseVariant.get("hgvs")[0]["name"])
        assertEquals(Arrays.asList("sid41", "sid411", "sid42", "sid422"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid41", "fid411", "fid42", "fid422"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("sid41", "sid411", "sid42", "sid422"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid41", "fid411", "fid42", "fid422"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("G", "G", "G", "G"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("mafAl"))
                        .sorted().collect(Collectors.toList()))
    }

    List<Document> getVariantsDataForHgvsNotPresent() {
        List<Document> variantDocumentList = new ArrayList<>()

        // case id collision - hgvs not already present
        // variant with uppercase ref and alt
        List<Document> hgvs51 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444A>G"))
        List<Document> files51 = Arrays.asList(new Document("sid", "sid51").append("fid", "fid51"))
        List<Document> stats51 = Arrays.asList(getVariantStats("sid51", "fid51", 0.51, 0.51, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr5", "A", "G",
                55555555, 55555555, 1, hgvs51, files51, stats51))

        // variant with lowercase ref and alt
        List<Document> hgvs52 = Arrays.asList(new Document("type", "genomic").append("name", "chr5:g.55555555a>g"))
        List<Document> files52 = Arrays.asList(new Document("sid", "sid52").append("fid", "fid52"),
                new Document("sid", "sid51").append("fid", "fid51"))
        List<Document> stats52 = Arrays.asList(getVariantStats("sid52", "fid52", 0.52, 0.52, "a", "0/0"),
                getVariantStats("sid51", "fid51", 0.51, 0.51, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr5", "a", "g",
                55555555, 55555555, 1, hgvs52, files52, stats52))

        // variant with uppercase ref and alt
        List<Document> hgvs61 = Arrays.asList(new Document("type", "genomic").append("name", "chr5:g.55555555A>G"))
        List<Document> files61 = Arrays.asList(new Document("sid", "sid61").append("fid", "fid61"))
        List<Document> stats61 = Arrays.asList(getVariantStats("sid61", "fid61", 0.61, 0.61, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr6", "A", "G",
                66666666, 66666666, 1, hgvs61, files61, stats61))

        // variant with lowercase ref and alt
        List<Document> hgvs62 = Arrays.asList(new Document("type", "genomic").append("name", "chr6:g.66666666a>g"))
        List<Document> files62 = Arrays.asList(new Document("sid", "sid62").append("fid", "fid62"))
        List<Document> stats62 = Arrays.asList(getVariantStats("sid62", "fid62", 0.62, 0.62, "a", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr6", "a", "g",
                66666666, 66666666, 1, hgvs62, files62, stats62))

        return variantDocumentList
    }

    def qcTestNoHgvsPresent(MongoTemplate mongoTemplate, String workingDir, String testDbName) {
        MongoCollection<VariantDocument> variantsColl = mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION)

        List<Document> upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr5_55555555_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        Document uppercaseVariant = upperCaseVariantList.get(0)
        assertEquals(Arrays.asList("[chr5:g.55555555A>G]", "chr4:g.44444444A>G"),
                Arrays.asList(uppercaseVariant.get('hgvs')[0]['name'], uppercaseVariant.get('hgvs')[1]['name'])
                        .stream().sorted().collect(Collectors.toList()))

        upperCaseVariantList = variantsColl.find(Filters.eq("_id", "chr6_66666666_A_G")).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        uppercaseVariant = upperCaseVariantList.get(0)
        assertEquals(Arrays.asList("[chr6:g.66666666A>G]", "chr5:g.55555555A>G"),
                Arrays.asList(uppercaseVariant.get('hgvs')[0]['name'], uppercaseVariant.get('hgvs')[1]['name'])
                        .stream().sorted().collect(Collectors.toList()))
    }

    List<Document> getVariantsDataForLargeRefAlt() {
        List<Document> variantDocumentList = new ArrayList<>()

        // case no id collision after remediation
        List<Document> hgvs11 = Arrays.asList(new Document("type", "genomic").append("name", "chr1:g.11111111a>g"))
        List<Document> files11 = Arrays.asList(new Document("sid", "sid11").append("fid", "fid11"))
        List<Document> stats11 = Arrays.asList(getVariantStats("sid11", "fid11", 0.11, 0.11, LOWERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr1", LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT,
                11111111, 11111111, 1, hgvs11, files11, stats11))

        // case id collision - all sid and fid are different
        // variant with uppercase ref and alt
        List<Document> hgvs21 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222A>G"))
        List<Document> files21 = Arrays.asList(new Document("sid", "sid21").append("fid", "fid21").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid211").append("fid", "fid211").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats21 = Arrays.asList(getVariantStats("sid21", "fid21", 0.21, 0.21, UPPERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid211", "fid211", 0.211, 0.211, UPPERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr2", UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT,
                22222222, 22222222, 1, hgvs21, files21, stats21))

        // variant with lowercase ref and alt
        List<Document> hgvs22 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222a>g"))
        List<Document> files22 = Arrays.asList(new Document("sid", "sid22").append("fid", "fid22").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid222").append("fid", "fid222").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats22 = Arrays.asList(getVariantStats("sid22", "fid22", 0.22, 0.22, LOWERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid222", "fid222", 0.222, 0.222, LOWERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr2", LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT,
                22222222, 22222222, 1, hgvs22, files22, stats22))


        // case id collision - fid has more than one file
        // variant with uppercase ref and alt
        List<Document> hgvs31 = Arrays.asList(new Document("type", "genomic").append("name", "chr3:g.33333333A>G"))
        List<Document> files31 = Arrays.asList(new Document("sid", "sid31").append("fid", "fid31"),
                new Document("sid", "sid311").append("fid", "fid311"))
        List<Document> stats31 = Arrays.asList(getVariantStats("sid31", "fid31", 0.31, 0.31, UPPERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid311", "fid311", 0.311, 0.311, UPPERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr3", UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT,
                33333333, 33333333, 1, hgvs31, files31, stats31))

        // variant with lowercase ref and alt
        List<Document> hgvs32 = Arrays.asList(new Document("type", "genomic").append("name", "chr3:g.33333333a>g"))
        List<Document> files32 = Arrays.asList(new Document("sid", "sid32").append("fid", "fid32"),
                new Document("sid", "sid322").append("fid", "fid322").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid31").append("fid", "fid31").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats32 = Arrays.asList(getVariantStats("sid32", "fid32", 0.32, 0.32, LOWERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid322", "fid322", 0.322, 0.322, LOWERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid31", "fid31", 0.31, 0.31, UPPERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr3", LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT,
                33333333, 33333333, 1, hgvs32, files32, stats32))


        // case id collision - common fid has just one file
        // variant with uppercase ref and alt
        List<Document> hgvs41 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444A>G"))
        List<Document> files41 = Arrays.asList(new Document("sid", "sid41").append("fid", "fid41").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid411").append("fid", "fid411").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats41 = Arrays.asList(getVariantStats("sid41", "fid41", 0.41, 0.41, UPPERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid411", "fid411", 0.411, 0.411, UPPERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr4", UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT,
                44444444, 44444444, 1, hgvs41, files41, stats41))

        // variant with lowercase ref and alt
        List<Document> hgvs42 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444a>g"))
        List<Document> files42 = Arrays.asList(new Document("sid", "sid42").append("fid", "fid42").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid422").append("fid", "fid422").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid411").append("fid", "fid411").append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats42 = Arrays.asList(getVariantStats("sid42", "fid42", 0.42, 0.42, LOWERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid422", "fid422", 0.422, 0.422, LOWERCASE_LARGE_REF, "0/0"),
                getVariantStats("sid411", "fid411", 0.411, 0.411, UPPERCASE_LARGE_REF, "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.INDEL.toString(), "chr4", LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT,
                44444444, 44444444, 1, hgvs42, files42, stats42))


        return variantDocumentList
    }

    void qcTestWithDifferentCasesLargeRefAlt(MongoTemplate mongoTemplate, String workingDir, String dbName) {
        MongoCollection<VariantDocument> variantsColl = mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION)
        MongoCollection<Document> annotationsColl = mongoTemplate.getCollection(RemediationApplication.ANNOTATIONS_COLLECTION)
        // case_no_id_collision

        // assert lowercase variant deleted
        List<Document> lowerCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT))).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        List<Document> upperCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT))).into([])
        assertEquals(1, upperCaseVariantList.size())
        // assert all things updated to uppercase in the updated variant
        Document upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals(UPPERCASE_LARGE_REF, upperCaseVariant.get("ref"))
        assertEquals(UPPERCASE_LARGE_ALT, upperCaseVariant.get("alt"))
        assertEquals(UPPERCASE_LARGE_REF, upperCaseVariant.get("st")[0]["mafAl"])

        // assert annotation remediation
        List<Document> lowercaseAnnot1 = annotationsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT) + "_82_82")).into([])
        List<Document> lowercaseAnnot2 = annotationsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT) + "_83_83")).into([])
        assertEquals(0, lowercaseAnnot1.size())
        assertEquals(0, lowercaseAnnot2.size())
        List<Document> uppercaseAnnot1 = annotationsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT) + "_82_82")).into([])
        List<Document> uppercaseAnnot2 = annotationsColl.find(Filters.eq("_id",
                buildVariantId("chr1", 11111111, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT) + "_83_83")).into([])
        assertEquals(1, uppercaseAnnot1.size())
        assertEquals(1, uppercaseAnnot2.size())


        // case_id_collision_all_sid_fid_diff

        //  assert lowercase variant deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr2", 22222222, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT))).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        upperCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr2", 22222222, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT))).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals(UPPERCASE_LARGE_REF, upperCaseVariant.get("ref"))
        assertEquals(UPPERCASE_LARGE_ALT, upperCaseVariant.get("alt"))
        assertEquals('chr2:g.22222222A>G', upperCaseVariant.get("hgvs")[0]["name"])
        assertEquals(Arrays.asList("sid21", "sid211", "sid22", "sid222"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid21", "fid211", "fid22", "fid222"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("sid21", "sid211", "sid22", "sid222"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid21", "fid211", "fid22", "fid222"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList(UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("mafAl"))
                        .sorted().collect(Collectors.toList()))


        // case_id_collision_sid_fid_has_more_than_one_file

        // assert lowercase variant is not deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr3", 33333333, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT))).into([])
        assertEquals(1, lowerCaseVariantList.size())
        upperCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr3", 33333333, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT))).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert the issue is logged in the file
        File nonMergedVariantFile = new File(Paths.get(workingDir, "non_merged_candidates", dbName + ".txt").toString())
        assertTrue(nonMergedVariantFile.exists())
        try (BufferedReader nmcFileReader = new BufferedReader(new FileReader(nonMergedVariantFile))) {
            assertTrue(nmcFileReader.readLine().startsWith("sid31,fid31,chr3_33333333"))
        }


        // case id collision - common fid has just one file

        //  assert lowercase variant deleted
        lowerCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr4", 44444444, LOWERCASE_LARGE_REF, LOWERCASE_LARGE_ALT))).into([])
        assertEquals(0, lowerCaseVariantList.size())
        // assert uppercase variant inserted
        upperCaseVariantList = variantsColl.find(Filters.eq("_id",
                buildVariantId("chr4", 44444444, UPPERCASE_LARGE_REF, UPPERCASE_LARGE_ALT))).into([])
        assertEquals(1, upperCaseVariantList.size())

        // assert all things updated to uppercase in the updated variant
        upperCaseVariant = upperCaseVariantList.get(0)
        assertEquals(UPPERCASE_LARGE_REF, upperCaseVariant.get("ref"))
        assertEquals(UPPERCASE_LARGE_ALT, upperCaseVariant.get("alt"))
        assertEquals('chr4:g.44444444A>G', upperCaseVariant.get("hgvs")[0]["name"])
        assertEquals(Arrays.asList("sid41", "sid411", "sid42", "sid422"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid41", "fid411", "fid42", "fid422"),
                ((List<Document>) upperCaseVariant.get('files')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("sid41", "sid411", "sid42", "sid422"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("sid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList("fid41", "fid411", "fid42", "fid422"),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("fid"))
                        .sorted().collect(Collectors.toList()))
        assertEquals(Arrays.asList(UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT, UPPERCASE_LARGE_ALT),
                ((List<Document>) upperCaseVariant.get('st')).stream()
                        .map(doc -> doc.get("mafAl"))
                        .sorted().collect(Collectors.toList()))
    }

    List<Document> getFilesDataForStatsCalculation() {
        return Arrays.asList(
                new Document("sid", "sid211").append("fid", "fid211").append("fname", "fname211")
                        .append("samp", new Document("samp211", 0).append("samp212", 1).append("samp213", 2)),
                // multiple entries for fid212 in the files collection
                new Document("sid", "sid211").append("fid", "fid212").append("fname", "fname2121")
                        .append("samp", new Document("samp121", 0).append("samp122", 1)).append("samp123", 2),
                new Document("sid", "sid211").append("fid", "fid212").append("fname", "fname2122")
                        .append("samp", new Document("samp121", 0).append("samp122", 1)).append("samp123", 2),

                new Document("sid", "sid311").append("fid", "fid311").append("fname", "fname311")
                        .append("samp", new Document("samp311", 0).append("samp312", 1).append("samp313", 2)),

                new Document("sid", "sid411").append("fid", "fid411").append("fname", "fname411")
                        .append("samp", new Document("samp411", 0).append("samp412", 1).append("samp413", 2)),
                // multiple entries for fid412 in the files collection
                new Document("sid", "sid411").append("fid", "fid412").append("fname", "fname4121")
                        .append("samp", new Document("samp421", 0).append("samp422", 1)).append("samp423", 2),
                new Document("sid", "sid411").append("fid", "fid412").append("fname", "fname4122")
                        .append("samp", new Document("samp421", 0).append("samp422", 1)).append("samp423", 2),

        )
    }

    List<Document> getVariantsDataForStatsCalculation() {
        List<Document> variantDocumentList = new ArrayList<>()

        // case id collision - all sid and fid are different
        // variant with uppercase ref and alt
        List<Document> hgvs21 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222A>G"))
        List<Document> files21 = Arrays.asList(
                // should calculate stats for this
                new Document("sid", "sid211").append("fid", "fid211")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                // should not calculate stats for this as no entry for sid411 in the files collections
                new Document("sid", "sid611").append("fid", "fid611")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        // variant stats has random entries - should be replaced with sensible calculated values
        List<Document> stats21 = Arrays.asList(getVariantStats("sid211", "fid211", 999, 999, "A", "0/0"),
                getVariantStats("sid311", "fid311", 999, 999, "A", "0/0"),
                getVariantStats("sid611", "fid611", 999, 999, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr2", "A", "G",
                22222222, 22222222, 1, hgvs21, files21, stats21))

        // variant with lowercase ref and alt
        List<Document> hgvs22 = Arrays.asList(new Document("type", "genomic").append("name", "chr2:g.22222222a>g"))
        List<Document> files22 = Arrays.asList(
                // should calculate stats for this
                new Document("sid", "sid311").append("fid", "fid311")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                // should not calculate for this as sid 211 and fid 212 has multiple entries in the files collection
                new Document("sid", "sid211").append("fid", "fid212")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats22 = Arrays.asList(getVariantStats("sid311", "fid311", 888, 888, "a", "0/0"),
                getVariantStats("sid312", "fid312", 999, 999, "a", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr2", "a", "g",
                22222222, 22222222, 1, hgvs22, files22, stats22))


        // case id collision - all sid and fid are different
        // variant with uppercase ref and alt
        List<Document> hgvs41 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444A>G"))
        List<Document> files41 = Arrays.asList(
                // should calculate stats for this
                new Document("sid", "sid411").append("fid", "fid411")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                // should not calculate stats for this as no entry for sid811 in the files collections
                new Document("sid", "sid811").append("fid", "fid811")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        // variant stats has random entries - should be replaced with sensible calculated values
        List<Document> stats41 = Arrays.asList(getVariantStats("sid411", "fid411", 999, 999, "A", "0/0"),
                getVariantStats("sid411", "fid411", 999, 999, "A", "0/0"),
                getVariantStats("sid611", "fid611", 999, 999, "A", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr4", "A", "G",
                44444444, 44444444, 1, hgvs41, files41, stats41))

        // variant with lowercase ref and alt
        List<Document> hgvs42 = Arrays.asList(new Document("type", "genomic").append("name", "chr4:g.44444444a>g"))
        List<Document> files42 = Arrays.asList(
                // should calculate stats for this
                new Document("sid", "sid311").append("fid", "fid311")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                // should not calculate for this as sid 211 and fid 212 has multiple entries in the files collection
                new Document("sid", "sid411").append("fid", "fid412")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))),
                new Document("sid", "sid411").append("fid", "fid411")
                        .append("samp", new Document("def", "0|0").append("0|1", Arrays.asList(1))))
        List<Document> stats42 = Arrays.asList(getVariantStats("sid411", "fid411", 888, 888, "a", "0/0"),
                getVariantStats("sid412", "fid412", 999, 999, "a", "0/0"))
        variantDocumentList.add(getVariantDocument(Variant.VariantType.SNV.toString(), "chr4", "a", "g",
                44444444, 44444444, 1, hgvs42, files42, stats42))


        return variantDocumentList
    }


    void qcTestWithStatsCalculation(MongoTemplate mongoTemplate, String workingDir, String dbName) {
        // case merge all sid fid are different
        List<VariantDocument> variantsList = mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION)
                .find(Filters.eq("_id", buildVariantId("chr2", 22222222, "A", "G"))).into([])
                .stream().map(doc -> mongoTemplate.getConverter().read(VariantDocument.class, doc))
                .collect(Collectors.toList())
        assertEquals(1, variantsList.size())

        Set<VariantStatsMongo> variantStatsList = variantsList.get(0).getVariantStatsMongo()
        assertEquals(2, variantStatsList.size())

        // assert the newly calculated stats
        VariantStatsMongo variantStatsForSid211Fid211 = variantStatsList.stream()
                .filter(st -> st.getStudyId().equals("sid211") && st.getFileId().equals("fid211"))
                .findFirst().get()
        Map<String, Integer> numOfGT = variantStatsForSid211Fid211.getNumGt()
        assertEquals(2, numOfGT.get("0|0"))
        assertEquals(1, numOfGT.get("0|1"))
        assertEquals(0.1666666716337204f, variantStatsForSid211Fid211.getMaf())
        assertEquals(0.3333333432674408f, variantStatsForSid211Fid211.getMgf())
        assertEquals("G", variantStatsForSid211Fid211.getMafAllele())
        assertEquals("0|1", variantStatsForSid211Fid211.getMgfGenotype())
        assertEquals(0, variantStatsForSid211Fid211.getMissingAlleles())
        assertEquals(0, variantStatsForSid211Fid211.getMissingGenotypes())

        VariantStatsMongo variantStatsForSid311Fid311 = variantStatsList.stream()
                .filter(st -> st.getStudyId().equals("sid311") && st.getFileId().equals("fid311"))
                .findFirst().get()
        numOfGT = variantStatsForSid311Fid311.getNumGt()
        assertEquals(2, numOfGT.get("0|0"))
        assertEquals(1, numOfGT.get("0|1"))
        assertEquals(0.1666666716337204f, variantStatsForSid311Fid311.getMaf())
        assertEquals(0.3333333432674408f, variantStatsForSid311Fid311.getMgf())
        assertEquals("G", variantStatsForSid311Fid311.getMafAllele())
        assertEquals("0|1", variantStatsForSid311Fid311.getMgfGenotype())
        assertEquals(0, variantStatsForSid311Fid311.getMissingAlleles())
        assertEquals(0, variantStatsForSid311Fid311.getMissingGenotypes())


        // case merge common sid fid has only one entry in the files collections

        variantsList = mongoTemplate.getCollection(RemediationApplication.VARIANTS_COLLECTION)
                .find(Filters.eq("_id", buildVariantId("chr4", 44444444, "A", "G"))).into([])
                .stream().map(doc -> mongoTemplate.getConverter().read(VariantDocument.class, doc))
                .collect(Collectors.toList())
        assertEquals(1, variantsList.size())

        variantStatsList = variantsList.get(0).getVariantStatsMongo()
        assertEquals(2, variantStatsList.size())

        // assert the newly calculated stats
        VariantStatsMongo variantStatsForSid411Fid411 = variantStatsList.stream()
                .filter(st -> st.getStudyId().equals("sid411") && st.getFileId().equals("fid411"))
                .findFirst().get()
        numOfGT = variantStatsForSid411Fid411.getNumGt()
        assertEquals(2, numOfGT.get("0|0"))
        assertEquals(1, numOfGT.get("0|1"))
        assertEquals(0.1666666716337204f, variantStatsForSid411Fid411.getMaf())
        assertEquals(0.3333333432674408f, variantStatsForSid411Fid411.getMgf())
        assertEquals("G", variantStatsForSid411Fid411.getMafAllele())
        assertEquals("0|1", variantStatsForSid411Fid411.getMgfGenotype())
        assertEquals(0, variantStatsForSid411Fid411.getMissingAlleles())
        assertEquals(0, variantStatsForSid411Fid411.getMissingGenotypes())

        variantStatsForSid311Fid311 = variantStatsList.stream()
                .filter(st -> st.getStudyId().equals("sid311") && st.getFileId().equals("fid311"))
                .findFirst().get()
        numOfGT = variantStatsForSid311Fid311.getNumGt()
        assertEquals(2, numOfGT.get("0|0"))
        assertEquals(1, numOfGT.get("0|1"))
        assertEquals(0.1666666716337204f, variantStatsForSid311Fid311.getMaf())
        assertEquals(0.3333333432674408f, variantStatsForSid311Fid311.getMgf())
        assertEquals("G", variantStatsForSid311Fid311.getMafAllele())
        assertEquals("0|1", variantStatsForSid311Fid311.getMgfGenotype())
        assertEquals(0, variantStatsForSid311Fid311.getMissingAlleles())
        assertEquals(0, variantStatsForSid311Fid311.getMissingGenotypes())
    }

    Document getFileDocument(String sid, String fid, String fileName) {
        return new Document().append("sid", sid).append("fid", fid).append("fname", fileName)
    }

    Document getVariantDocument(String variantType, String chromosome, String ref, String alt, int start,
                                int end, int length, List<Document> hgvs, List<Document> files, List<Document> stats) {
        return new Document()
                .append("_id", buildVariantId(chromosome, start, ref, alt))
                .append("type", variantType)
                .append("chr", chromosome)
                .append("ref", ref)
                .append("alt", alt)
                .append("start", start)
                .append("end", end)
                .append("len", length)
                .append("hgvs", hgvs)
                .append("files", files)
                .append("st", stats)
    }

    Document getVariantStats(String studyId, String fileId, float maf, float mgf, String mafAl, String mgfAl) {
        return new Document()
                .append("sid", studyId)
                .append("fid", fileId)
                .append("maf", maf)
                .append("mgf", mgf)
                .append("mafAl", mafAl)
                .append("mgfGt", mgfAl)
    }

    String buildVariantId(String chromosome, int start, String reference, String alternate) {
        StringBuilder builder = new StringBuilder(chromosome)
        builder.append("_")
        builder.append(start)
        builder.append("_")
        if (!reference.equals("-")) {
            if (reference.length() < 50) {
                builder.append(reference)
            } else {
                builder.append(new String(CryptoUtils.encryptSha1(reference)))
            }
        }

        builder.append("_")
        if (!alternate.equals("-")) {
            if (alternate.length() < 50) {
                builder.append(alternate)
            } else {
                builder.append(new String(CryptoUtils.encryptSha1(alternate)))
            }
        }
        return builder.toString()
    }

}