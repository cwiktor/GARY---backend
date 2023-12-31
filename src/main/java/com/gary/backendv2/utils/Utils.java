package com.gary.backendv2.utils;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import com.gary.backendv2.exception.HttpException;
import com.gary.backendv2.model.dto.request.users.RegisterEmployeeRequest;
import com.gary.backendv2.model.dto.response.WorkScheduleResponse;
import com.gary.backendv2.model.security.ResetPasswordToken;
import com.gary.backendv2.model.users.User;
import com.gary.backendv2.model.users.employees.AbstractEmployee;
import com.gary.backendv2.model.users.employees.MappedSchedule;
import com.gary.backendv2.model.users.employees.WorkSchedule;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.FileCopyUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;


import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

@Slf4j
public class Utils {

    @SneakyThrows
    public static String POJOtoJsonString(Object object) {
        // java pls
        boolean isNotWorkSchedule = !(object instanceof WorkSchedule);
        boolean isNotMap = !(object instanceof Map<?,?>);
        if (isNotWorkSchedule == isNotMap)  {
            throw new RuntimeException("Unsupported types in POJOtoJsonString method must be one of: [WorkSchedule, Map<?,?>]");
        }

        ObjectMapper mapper = new ObjectMapper();

        SimpleModule module = new SimpleModule();
        module.addSerializer(MappedSchedule.class, new Utils.ScheduleSerializer());
        mapper.registerModule(module);
        mapper.registerModule(new JSR310Module());

        ObjectWriter ow = mapper.writer().withDefaultPrettyPrinter();

        return ow.writeValueAsString(object);
    }

    public static WorkScheduleResponse createWorkScheduleResponse(AbstractEmployee employee) {
        WorkSchedule newSchedule = employee.getWorkSchedule();
        MappedSchedule mappedSchedule = newSchedule.getMappedSchedule();

        WorkScheduleResponse response = new WorkScheduleResponse();

        for (var kv : mappedSchedule.getTimeTable().entrySet()) {
            response.getSchedule().put(
                    String.valueOf(kv.getKey()),
                    new RegisterEmployeeRequest.ScheduleDto(
                            mappedSchedule.getWorkingHours(kv.getKey()).getLeft().toString(),
                            mappedSchedule.getWorkingHours(kv.getKey()).getRight().toString()));
        }
        return response;
    }

    public static ResetPasswordToken generatePasswordResetTokenForUser(User user) {
        String token = generatePasswordResetToken();

        ResetPasswordToken resetPasswordToken = new ResetPasswordToken();
        resetPasswordToken.setUser(user);
        resetPasswordToken.setToken(token);
        resetPasswordToken.setValid(true);
        resetPasswordToken.setCreatedAt(LocalDateTime.now());

        return resetPasswordToken;
    }

    @SneakyThrows
    private static String generatePasswordResetToken() {
        String chrs = "0123456789abcdefghijklmnopqrstuvwxyz-_ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        SecureRandom secureRandom = SecureRandom.getInstanceStrong();

        return secureRandom
                .ints(24, 0, chrs.length())
                .mapToObj(chrs::charAt)
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();
    }

    public static String loadClasspathResource(String classpath) {
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(classpath);

        String fileContents = "";
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            fileContents = FileCopyUtils.copyToString(reader);
        }
        catch (IOException e) {
            log.error("Error loading classpath resource: {}", classpath);
        }

        return fileContents;
        
    }

    private static final int BANDCODE_LENGHT = 3;
    @SneakyThrows
    public static String generateBandCode() {
        DictionaryIndexer indexer = DictionaryIndexer.getInstance();

        List<String> bandCode = new ArrayList<>();
        for (int i = 0; i < BANDCODE_LENGHT; i++) {
            String randomLetter = String.valueOf((char) ThreadLocalRandom.current().nextInt('a', 'z'));
            List<String> codeCandidates = indexer.indexedDictionary.get(randomLetter);
            int size = codeCandidates.size();

            String randomWord = codeCandidates.get(ThreadLocalRandom.current().nextInt(0, size));
            bandCode.add(randomWord);
        }

        return String.join("-", bandCode);
    }

    public static class ScheduleDeserializer extends StdDeserializer<MappedSchedule> {

        public ScheduleDeserializer() {
            this(null);
        }

        public ScheduleDeserializer(Class<MappedSchedule> vc) {
            super(vc);
        }

        @Override
        public MappedSchedule deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JacksonException {
            JsonNode rootNode = jp.getCodec().readTree(jp);
            if (rootNode == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Cannot parse Work Schedule definition, check your schema");
            }

            MappedSchedule mappedSchedule = new MappedSchedule();

            for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                JsonNode weekDayNode = rootNode.get(dayOfWeek.toString());
                JsonNode startNode;
                JsonNode endNode;

                if (weekDayNode == null) {
                    continue;
                }

                startNode = weekDayNode.get("start");
                endNode = weekDayNode.get("end");

                if (Stream.of(startNode, endNode).anyMatch(Objects::isNull)) {
                    throw new RuntimeException("Cannot parse Work Schedule definition, check your schema");
                }

                String startHour = startNode.asText();
                String endHour = endNode.asText();

                String[] start = startHour.replace("\"", "").split(":");
                String[] end = endHour.replace("\"", "").split(":");
                if (start.length != 2 || end.length != 2) {
                    throw new RuntimeException(new Throwable("Invalid JSON of work-schedule definition"));
                }

                checkTimeFormat(start);
                checkTimeFormat(end);

                LocalTime startTime = LocalTime.of(Integer.parseInt(start[0]), Integer.parseInt(start[1]));
                LocalTime endTime = LocalTime.of(Integer.parseInt(end[0]), Integer.parseInt(end[1]));
                Pair<LocalTime, LocalTime> workingHours = Pair.of(startTime, endTime);

                mappedSchedule.getTimeTable().put(dayOfWeek, workingHours);
            }


            return mappedSchedule;
        }

        private void checkTimeFormat(String[] time) {
            if (!(Integer.parseInt(time[0]) >= 0 && Integer.parseInt(time[0]) < 24)) {
                throw new RuntimeException(new Throwable("Hours should be in range [0, 24)"));
            }
            if (!(Integer.parseInt(time[1]) >= 0 && Integer.parseInt(time[1]) < 60)) {
                throw new RuntimeException(new Throwable("Minutes should be in range [0, 60)"));
            }
        }
    }

    public static class ScheduleSerializer extends StdSerializer<MappedSchedule>  {
        public ScheduleSerializer() {
            this(null);
        }

        public ScheduleSerializer(Class<MappedSchedule> t) {
            super(t);
        }

        @Override
        public void serialize(MappedSchedule value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
            var timeTable = value.getTimeTable();

            jgen.writeStartObject();
            for (var kv : timeTable.entrySet()) {
                jgen.writeFieldName(kv.getKey().toString());
                jgen.writeStartObject();
                jgen.writeFieldName("start");
                jgen.writeString(kv.getValue().getLeft().toString());
                jgen.writeFieldName("end");
                jgen.writeString(kv.getValue().getRight().toString());
                jgen.writeEndObject();
            }
            jgen.writeEndObject();
        }
    }

    public static String getTutorialCss(){
        return Utils.loadClasspathResource("classpath:templates/style.css");
    }
}
