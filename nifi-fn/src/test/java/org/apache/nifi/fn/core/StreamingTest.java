/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.fn.core;

import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processors.standard.PutFile;
import org.apache.nifi.processors.standard.ReplaceText;
import org.apache.nifi.processors.standard.SplitText;
import org.apache.nifi.processors.standard.TailFile;
import org.apache.nifi.registry.VariableRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StreamingTest {

    @org.junit.Test
    public void Scenario1_Test() throws InvocationTargetException, IllegalAccessException, IOException, InterruptedException {
        ///////////////////////////////////////////
        // Setup
        ///////////////////////////////////////////
        VariableRegistry registry = VariableRegistry.EMPTY_REGISTRY;
        boolean materializeData = true;
        FnControllerServiceLookup serviceLookup = new FnControllerServiceLookup();
        File file = new File("/tmp/nififn/input/test.txt");
        file.getParentFile().mkdirs();
        file.createNewFile();
        try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
            out.print("hello world");
        }

        ///////////////////////////////////////////
        // Build Flow
        ///////////////////////////////////////////
        FnProcessorWrapper tailFile = new FnProcessorWrapper(new TailFile(), null, serviceLookup, registry, materializeData);
        tailFile.setProperty("File to Tail","/tmp/nififn/input/test.txt");

        Set<Relationship> relationships = tailFile.getProcessor().getRelationships();
        Relationship tailFile_Success = relationships.stream().filter(r->r.getName().equals("success")).findFirst().get();

        FnProcessorWrapper splitText = tailFile.addChild(new SplitText(), tailFile_Success);
        splitText.setProperty(SplitText.LINE_SPLIT_COUNT,"1");
        splitText.addAutoTermination(SplitText.REL_FAILURE);
        splitText.addAutoTermination(SplitText.REL_ORIGINAL);

        FnProcessorWrapper replaceText = splitText.addChild(new ReplaceText(), SplitText.REL_SPLITS);
        replaceText.setProperty(ReplaceText.REPLACEMENT_VALUE,"$1!!!");
        replaceText.addAutoTermination(ReplaceText.REL_FAILURE);


        FnProcessorWrapper putFile = replaceText.addChild(new PutFile(), ReplaceText.REL_SUCCESS);
        putFile.addAutoTermination(PutFile.REL_FAILURE);
        putFile.addAutoTermination(PutFile.REL_SUCCESS);
        putFile.setProperty(PutFile.DIRECTORY,"/tmp/nififn/output/");
        putFile.setProperty(PutFile.CONFLICT_RESOLUTION, PutFile.REPLACE_RESOLUTION);



        ///////////////////////////////////////////
        // Run Flow
        ///////////////////////////////////////////

        FnFlow flow = new FnFlow(tailFile);
        Queue<FnFlowFile> output = new LinkedList<>();
        AtomicBoolean successful = new AtomicBoolean(true);
        Thread t = new Thread(()->
                successful.set(flow.run(output))
        );

        Thread.sleep(5000);

        ///////////////////////////////////////////
        // Validate
        ///////////////////////////////////////////

        String outputFile = "/tmp/nififn/output/test.txt";
        assertTrue(new File(outputFile).isFile());

        List<String> lines = Files.readAllLines(Paths.get(outputFile), StandardCharsets.UTF_8);
        assertEquals(1,lines.size());
        System.out.println("data: "+lines.get(0));
        assertEquals("hello world!!!", lines.get(0));


        System.out.println("Stopping...");
        flow.shutdown();
        t.join();

        assertTrue(new File(outputFile).isFile());

        lines = Files.readAllLines(Paths.get(outputFile), StandardCharsets.UTF_8);
        assertTrue(successful.get());
        assertTrue(output.isEmpty());
        assertEquals(1,lines.size());
        assertEquals("hello world!!!", lines.get(0));
    }
}