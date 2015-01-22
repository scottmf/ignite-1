/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gridgain.grid.kernal.processors.hadoop;

import com.google.common.base.Joiner;
import org.apache.ignite.*;
import org.apache.ignite.fs.*;
import org.apache.ignite.hadoop.*;
import org.gridgain.grid.kernal.processors.ggfs.*;
import org.gridgain.grid.kernal.processors.hadoop.counter.*;
import org.gridgain.grid.kernal.processors.hadoop.jobtracker.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;
import org.jdk8.backport.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Test of integration with Hadoop client via command line interface.
 */
public class GridHadoopCommandLineTest extends GridCommonAbstractTest {
    /** GGFS instance. */
    private GridGgfsEx ggfs;

    /** */
    private static final String ggfsName = "ggfs";

    /** */
    private static File testWorkDir;

    /** */
    private static String hadoopHome;

    /** */
    private static String hiveHome;

    /** */
    private static File examplesJar;

    /**
     *
     * @param path File name.
     * @param wordCounts Words and counts.
     * @throws Exception If failed.
     */
    private void generateTestFile(File path, Object... wordCounts) throws Exception {
        List<String> wordsArr = new ArrayList<>();

        //Generating
        for (int i = 0; i < wordCounts.length; i += 2) {
            String word = (String) wordCounts[i];
            int cnt = (Integer) wordCounts[i + 1];

            while (cnt-- > 0)
                wordsArr.add(word);
        }

        //Shuffling
        for (int i = 0; i < wordsArr.size(); i++) {
            int j = (int)(Math.random() * wordsArr.size());

            Collections.swap(wordsArr, i, j);
        }

        //Writing file
        try (PrintWriter writer = new PrintWriter(path)) {
            int j = 0;

            while (j < wordsArr.size()) {
                int i = 5 + (int)(Math.random() * 5);

                List<String> subList = wordsArr.subList(j, Math.min(j + i, wordsArr.size()));
                j += i;

                writer.println(Joiner.on(' ').join(subList));
            }

            writer.flush();
        }
    }

    /**
     * Generates two data files to join its with Hive.
     *
     * @throws FileNotFoundException If failed.
     */
    private void generateHiveTestFiles() throws FileNotFoundException {
        try (PrintWriter writerA = new PrintWriter(new File(testWorkDir, "data-a"));
             PrintWriter writerB = new PrintWriter(new File(testWorkDir, "data-b"))) {
            char sep = '\t';

            int idB = 0;
            int idA = 0;
            int v = 1000;

            for (int i = 0; i < 1000; i++) {
                writerA.print(idA++);
                writerA.print(sep);
                writerA.println(idB);

                writerB.print(idB++);
                writerB.print(sep);
                writerB.println(v += 2);

                writerB.print(idB++);
                writerB.print(sep);
                writerB.println(v += 2);
            }

            writerA.flush();
            writerB.flush();
        }
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        hiveHome = IgniteSystemProperties.getString("HIVE_HOME");

        assertFalse("HIVE_HOME hasn't been set.", F.isEmpty(hiveHome));

        hadoopHome = IgniteSystemProperties.getString("HADOOP_HOME");

        assertFalse("HADOOP_HOME hasn't been set.", F.isEmpty(hadoopHome));

        String mapredHome = hadoopHome + "/share/hadoop/mapreduce";

        File[] fileList = new File(mapredHome).listFiles(new FileFilter() {
            @Override public boolean accept(File pathname) {
                return pathname.getName().startsWith("hadoop-mapreduce-examples-") &&
                    pathname.getName().endsWith(".jar");
            }
        });

        assertEquals("Invalid hadoop distribution.", 1, fileList.length);

        examplesJar = fileList[0];

        testWorkDir = Files.createTempDirectory("hadoop-cli-test").toFile();

        U.copy(U.resolveGridGainPath("docs/core-site.gridgain.xml"), new File(testWorkDir, "core-site.xml"), false);

        File srcFile = U.resolveGridGainPath("docs/mapred-site.gridgain.xml");
        File dstFile = new File(testWorkDir, "mapred-site.xml");

        try (BufferedReader in = new BufferedReader(new FileReader(srcFile));
             PrintWriter out = new PrintWriter(dstFile)) {
            String line;

            while ((line = in.readLine()) != null) {
                if (line.startsWith("</configuration>"))
                    out.println(
                        "    <property>\n" +
                        "        <name>" + GridHadoopUtils.JOB_COUNTER_WRITER_PROPERTY + "</name>\n" +
                        "        <value>" + GridHadoopFSCounterWriter.class.getName() + "</value>\n" +
                        "    </property>\n");

                out.println(line);
            }

            out.flush();
        }

        generateTestFile(new File(testWorkDir, "test-data"), "red", 100, "green", 200, "blue", 150, "yellow", 50);

        generateHiveTestFiles();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();

        U.delete(testWorkDir);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ggfs = (GridGgfsEx) Ignition.start("config/hadoop/default-config.xml").fileSystem(ggfsName);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids(true);
    }

    /**
     * Creates the process build with appropriate environment to run Hadoop CLI.
     *
     * @return Process builder.
     */
    private ProcessBuilder createProcessBuilder() {
        String sep = ":";

        String ggClsPath = GridHadoopJob.class.getProtectionDomain().getCodeSource().getLocation().getPath() + sep +
            GridHadoopJobTracker.class.getProtectionDomain().getCodeSource().getLocation().getPath() + sep +
            ConcurrentHashMap8.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        ProcessBuilder res = new ProcessBuilder();

        res.environment().put("HADOOP_HOME", hadoopHome);
        res.environment().put("HADOOP_CLASSPATH", ggClsPath);
        res.environment().put("HADOOP_CONF_DIR", testWorkDir.getAbsolutePath());

        res.redirectErrorStream(true);

        return res;
    }

    /**
     * Waits for process exit and prints the its output.
     *
     * @param proc Process.
     * @return Exit code.
     * @throws Exception If failed.
     */
    private int watchProcess(Process proc) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));

        String line;

        while ((line = reader.readLine()) != null)
            log().info(line);

        return proc.waitFor();
    }

    /**
     * Executes Hadoop command line tool.
     *
     * @param args Arguments for Hadoop command line tool.
     * @return Process exit code.
     * @throws Exception If failed.
     */
    private int executeHadoopCmd(String... args) throws Exception {
        ProcessBuilder procBuilder = createProcessBuilder();

        List<String> cmd = new ArrayList<>();

        cmd.add(hadoopHome + "/bin/hadoop");
        cmd.addAll(Arrays.asList(args));

        procBuilder.command(cmd);

        log().info("Execute: " + procBuilder.command());

        return watchProcess(procBuilder.start());
    }

    /**
     * Executes Hive query.
     *
     * @param qry Query.
     * @return Process exit code.
     * @throws Exception If failed.
     */
    private int executeHiveQuery(String qry) throws Exception {
        ProcessBuilder procBuilder = createProcessBuilder();

        List<String> cmd = new ArrayList<>();

        procBuilder.command(cmd);

        cmd.add(hiveHome + "/bin/hive");

        cmd.add("--hiveconf");
        cmd.add("hive.rpc.query.plan=true");

        cmd.add("--hiveconf");
        cmd.add("javax.jdo.option.ConnectionURL=jdbc:derby:" + testWorkDir.getAbsolutePath() + "/metastore_db;" +
            "databaseName=metastore_db;create=true");

        cmd.add("-e");
        cmd.add(qry);

        procBuilder.command(cmd);

        log().info("Execute: " + procBuilder.command());

        return watchProcess(procBuilder.start());
    }

    /**
     * Tests Hadoop command line integration.
     */
    public void testHadoopCommandLine() throws Exception {
        assertEquals(0, executeHadoopCmd("fs", "-ls", "/"));

        assertEquals(0, executeHadoopCmd("fs", "-mkdir", "/input"));

        assertEquals(0, executeHadoopCmd("fs", "-put", new File(testWorkDir, "test-data").getAbsolutePath(), "/input"));

        assertTrue(ggfs.exists(new IgniteFsPath("/input/test-data")));

        assertEquals(0, executeHadoopCmd("jar", examplesJar.getAbsolutePath(), "wordcount", "/input", "/output"));

        IgniteFsPath path = new IgniteFsPath("/user/" + System.getProperty("user.name") + "/");

        assertTrue(ggfs.exists(path));

        IgniteFsPath jobStatPath = null;

        for (IgniteFsPath jobPath : ggfs.listPaths(path)) {
            assertNull(jobStatPath);

            jobStatPath = jobPath;
        }

        File locStatFile = new File(testWorkDir, "performance");

        assertEquals(0, executeHadoopCmd("fs", "-get", jobStatPath.toString() + "/performance", locStatFile.toString()));

        long evtCnt = GridHadoopTestUtils.simpleCheckJobStatFile(new BufferedReader(new FileReader(locStatFile)));

        assertTrue(evtCnt >= 22); //It's the minimum amount of events for job with combiner.

        assertTrue(ggfs.exists(new IgniteFsPath("/output")));

        BufferedReader in = new BufferedReader(new InputStreamReader(ggfs.open(new IgniteFsPath("/output/part-r-00000"))));

        List<String> res = new ArrayList<>();

        String line;

        while ((line = in.readLine()) != null)
            res.add(line);

        Collections.sort(res);

        assertEquals("[blue\t150, green\t200, red\t100, yellow\t50]", res.toString());
    }

    /**
     * Runs query check result.
     *
     * @param expRes Expected result.
     * @param qry Query.
     * @throws Exception If failed.
     */
    private void checkQuery(String expRes, String qry) throws Exception {
        assertEquals(0, executeHiveQuery("drop table if exists result"));

        assertEquals(0, executeHiveQuery(
            "create table result " +
            "row format delimited fields terminated by ' ' " +
            "stored as textfile " +
            "location '/result' as " + qry
        ));

        GridGgfsInputStreamAdapter in = ggfs.open(new IgniteFsPath("/result/000000_0"));

        byte[] buf = new byte[(int) in.length()];

        in.read(buf);

        assertEquals(expRes, new String(buf));
    }

    /**
     * Tests Hive integration.
     */
    public void testHiveCommandLine() throws Exception {
        assertEquals(0, executeHiveQuery(
            "create table table_a (" +
                "id_a int," +
                "id_b int" +
            ") " +
            "row format delimited fields terminated by '\\t'" +
            "stored as textfile " +
            "location '/table-a'"
        ));

        assertEquals(0, executeHadoopCmd("fs", "-put", new File(testWorkDir, "data-a").getAbsolutePath(), "/table-a"));

        assertEquals(0, executeHiveQuery(
            "create table table_b (" +
                "id_b int," +
                "rndv int" +
            ") " +
            "row format delimited fields terminated by '\\t'" +
            "stored as textfile " +
            "location '/table-b'"
        ));

        assertEquals(0, executeHadoopCmd("fs", "-put", new File(testWorkDir, "data-b").getAbsolutePath(), "/table-b"));

        checkQuery(
            "0 0\n" +
            "1 2\n" +
            "2 4\n" +
            "3 6\n" +
            "4 8\n" +
            "5 10\n" +
            "6 12\n" +
            "7 14\n" +
            "8 16\n" +
            "9 18\n",
            "select * from table_a order by id_a limit 10"
        );

        checkQuery("2000\n", "select count(id_b) from table_b");

        checkQuery(
            "250 500 2002\n" +
            "251 502 2006\n" +
            "252 504 2010\n" +
            "253 506 2014\n" +
            "254 508 2018\n" +
            "255 510 2022\n" +
            "256 512 2026\n" +
            "257 514 2030\n" +
            "258 516 2034\n" +
            "259 518 2038\n",
            "select a.id_a, a.id_b, b.rndv" +
            " from table_a a" +
            " inner join table_b b on a.id_b = b.id_b" +
            " where b.rndv > 2000" +
            " order by a.id_a limit 10"
        );

        checkQuery("1000\n", "select count(b.id_b) from table_a a inner join table_b b on a.id_b = b.id_b");
    }
}
