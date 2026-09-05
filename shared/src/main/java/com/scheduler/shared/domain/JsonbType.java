package com.scheduler.shared.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Hibernate UserType that maps a Java String to PostgreSQL JSONB.
 *
 * <p>Why a UserType instead of an AttributeConverter?</p>
 * <p>AttributeConverter maps to SQL VARCHAR (Types.VARCHAR). PostgreSQL rejects
 * implicit casts from VARCHAR to JSONB, causing:
 * {@code ERROR: column "payload" is of type jsonb but expression is of type character varying}</p>
 *
 * <p>UserType lets us set the JDBC parameter using {@link org.postgresql.util.PGobject},
 * which tells the PostgreSQL driver that the value is already typed as JSONB, bypassing
 * the implicit cast requirement entirely.</p>
 */
public class JsonbType implements UserType<String> {

    public static final String TYPE_NAME = "com.scheduler.shared.domain.JsonbType";

    @Override
    public int getSqlType() {
        return Types.OTHER;  // PostgreSQL JSONB maps to OTHER in JDBC
    }

    @Override
    public Class<String> returnedClass() {
        return String.class;
    }

    @Override
    public boolean equals(String x, String y) {
        if (x == null) return y == null;
        return x.equals(y);
    }

    @Override
    public int hashCode(String x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public String nullSafeGet(ResultSet rs, int position,
                              SharedSessionContractImplementor session,
                              Object owner) throws SQLException {
        String value = rs.getString(position);
        return rs.wasNull() ? null : value;
    }

    @Override
    public void nullSafeSet(PreparedStatement st, String value, int index,
                            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            // PGobject is the PostgreSQL JDBC driver's way to pass typed objects.
            // We use reflection to avoid a compile-time dependency on the driver
            // (which is declared as 'runtime' scope in the shared pom).
            // At runtime, the driver is always on the classpath — no NPE risk.
            try {
                Object pgo = Class.forName("org.postgresql.util.PGobject")
                    .getDeclaredConstructor()
                    .newInstance();
                pgo.getClass().getMethod("setType", String.class).invoke(pgo, "jsonb");
                pgo.getClass().getMethod("setValue", String.class).invoke(pgo, value);
                st.setObject(index, pgo);
            } catch (ReflectiveOperationException e) {
                throw new SQLException("Failed to set JSONB value via PGobject: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public String deepCopy(String value) {
        return value; // String is immutable — safe to share reference
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(String value) {
        return value;
    }

    @Override
    public String assemble(Serializable cached, Object owner) {
        return (String) cached;
    }
}
