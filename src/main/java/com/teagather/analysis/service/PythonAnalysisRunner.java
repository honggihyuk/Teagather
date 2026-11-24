package com.teagather.analysis.service;

import com.teagather.analysis.dto.AnalysisInput;
import com.teagather.analysis.dto.AnalysisResult;
import com.teagather.common.util.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// PyTorch 분석 스크립트를 별도 프로세스로 실행한다
//   python analytics/teagather_analysis.py --input work/input.json --output work/output.json
// 표준 출력은 로그로 남기고, 결과는 output.json 에서 읽는다
@Slf4j
@Component
public class PythonAnalysisRunner {

    @Value("${app.analysis.python:python}")
    private String python;

    @Value("${app.analysis.script:analytics/teagather_analysis.py}")
    private String script;

    @Value("${app.analysis.work-dir:analytics/work}")
    private String workDir;

    @Value("${app.analysis.timeout-seconds:180}")
    private long timeoutSeconds;

    public AnalysisResult run(AnalysisInput input) throws Exception {
        File scriptFile = new File(script).getAbsoluteFile();
        if (!scriptFile.isFile()) throw new IllegalStateException("분석 스크립트가 없어요 : " + scriptFile);
        File dir = new File(workDir).getAbsoluteFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("작업 폴더를 만들 수 없어요 : " + dir);
        File in = new File(dir, "input.json");
        File out = new File(dir, "output.json");
        if (out.exists() && !out.delete()) log.warn("이전 결과 파일을 지우지 못했어요 : {}", out);
        Json.write(in, input);

        List<String> cmd = new ArrayList<>(List.of(python, scriptFile.getPath(), "--input", in.getPath(), "--output", out.getPath()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        log.info("=======> PyTorch 분석 시작 : {}", String.join(" ", cmd));
        Process p = pb.start();

        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[analysis] {}", line);
                    synchronized (tail) {
                        tail.append(line).append('\n');
                        if (tail.length() > 2000) tail.delete(0, tail.length() - 2000);
                    }
                }
            } catch (Exception ignored) {
                // 프로세스가 끝나면 스트림이 닫힌다
            }
        }, "analysis-log");
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("분석이 " + timeoutSeconds + "초 안에 끝나지 않았어요.");
        }
        reader.join(2000);
        if (p.exitValue() != 0 || !out.isFile()) {
            String last;
            synchronized (tail) { last = tail.toString().trim(); }
            String[] lines = last.split("\n");
            throw new IllegalStateException("파이썬 종료 코드 " + p.exitValue() + " : " + lines[lines.length - 1]);
        }
        return Json.read(out, AnalysisResult.class);
    }
}
