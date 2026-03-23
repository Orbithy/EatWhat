package you.v50to.eatwhat.config;

import org.locationtech.jts.geom.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.*;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        SimpleModule epochMillisModule = new SimpleModule();

        epochMillisModule.addSerializer(LocalDateTime.class, new StdSerializer<>(LocalDateTime.class) {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext provider){
                gen.writeNumber(value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
        });
        epochMillisModule.addSerializer(LocalDate.class, new StdSerializer<>(LocalDate.class) {
            @Override
            public void serialize(LocalDate value, JsonGenerator gen, SerializationContext provider) {
                gen.writeNumber(value.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
        });
        epochMillisModule.addSerializer(OffsetDateTime.class, new StdSerializer<>(OffsetDateTime.class) {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializationContext provider) {
                gen.writeNumber(value.toInstant().toEpochMilli());
            }
        });
        epochMillisModule.addSerializer(Instant.class, new StdSerializer<>(Instant.class) {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializationContext provider) {
                gen.writeNumber(value.toEpochMilli());
            }
        });
        epochMillisModule.addSerializer(ZonedDateTime.class, new StdSerializer<>(ZonedDateTime.class) {
            @Override
            public void serialize(ZonedDateTime value, JsonGenerator gen, SerializationContext provider) {
                gen.writeNumber(value.toInstant().toEpochMilli());
            }
        });
        GeometrySerializer geometrySerializer = new GeometrySerializer();
        epochMillisModule.addSerializer(Geometry.class, geometrySerializer);
        epochMillisModule.addSerializer(Point.class, geometrySerializer);

        return JsonMapper.builder()
                .addModule(epochMillisModule)
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public static class GeometrySerializer extends StdSerializer<Geometry> {

        GeometrySerializer() {
            super(Geometry.class);
        }

        @Override
        public void serialize(Geometry value, JsonGenerator gen, SerializationContext provider) {
            gen.writeStartObject();
            gen.writeStringProperty("type", value.getGeometryType());
            gen.writeName("coordinates");
            writeCoordinates(gen, value);
            if (value.getSRID() > 0) {
                gen.writeNumberProperty("srid", value.getSRID());
            }
            gen.writeEndObject();
        }

        private void writeCoordinates(JsonGenerator gen, Geometry geometry) {
            if (geometry instanceof Point point) {
                writeCoordinate(gen, point.getCoordinate());
                return;
            }
            if (geometry instanceof LineString lineString) {
                writeCoordinateArray(gen, lineString.getCoordinates());
                return;
            }
            if (geometry instanceof Polygon polygon) {
                gen.writeStartArray();
                writeCoordinateArray(gen, polygon.getExteriorRing().getCoordinates());
                for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                    writeCoordinateArray(gen, polygon.getInteriorRingN(i).getCoordinates());
                }
                gen.writeEndArray();
                return;
            }
            if (geometry instanceof MultiPoint multiPoint) {
                gen.writeStartArray();
                for (int i = 0; i < multiPoint.getNumGeometries(); i++) {
                    writeCoordinate(gen, ((Point) multiPoint.getGeometryN(i)).getCoordinate());
                }
                gen.writeEndArray();
                return;
            }
            if (geometry instanceof GeometryCollection collection) {
                gen.writeStartArray();
                for (int i = 0; i < collection.getNumGeometries(); i++) {
                    writeCoordinates(gen, collection.getGeometryN(i));
                }
                gen.writeEndArray();
                return;
            }
            writeCoordinateArray(gen, geometry.getCoordinates());
        }

        private void writeCoordinateArray(JsonGenerator gen, Coordinate[] coordinates) {
            gen.writeStartArray();
            for (Coordinate coordinate : coordinates) {
                writeCoordinate(gen, coordinate);
            }
            gen.writeEndArray();
        }

        private void writeCoordinate(JsonGenerator gen, Coordinate coordinate) {
            gen.writeStartArray();
            gen.writeNumber(coordinate.getX());
            gen.writeNumber(coordinate.getY());
            if (!Double.isNaN(coordinate.getZ())) {
                gen.writeNumber(coordinate.getZ());
            }
            gen.writeEndArray();
        }
    }
}
