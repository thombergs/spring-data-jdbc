/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jdbc.core;

import static java.util.Collections.*;
import static org.assertj.core.api.Assertions.*;

import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.assertj.core.api.SoftAssertions;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.jdbc.testing.DatabaseProfileValueSource;
import org.springframework.data.jdbc.testing.TestConfiguration;
import org.springframework.data.relational.core.conversion.RelationalConverter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.test.annotation.IfProfileValue;
import org.springframework.test.annotation.ProfileValueSourceConfiguration;
import org.springframework.test.annotation.ProfileValueUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link JdbcAggregateTemplate}.
 *
 * @author Jens Schauder
 * @author Thomas Lang
 * @author Mark Paluch
 * @author Tom Hombergs
 */
@ContextConfiguration
@Transactional
@ProfileValueSourceConfiguration(DatabaseProfileValueSource.class)
public class JdbcAggregateTemplateIntegrationTests {

	@ClassRule public static final SpringClassRule classRule = new SpringClassRule();
	@Rule public SpringMethodRule methodRule = new SpringMethodRule();
	@Autowired JdbcAggregateOperations template;

	LegoSet legoSet = createLegoSet();

	@Test // DATAJDBC-112
	public void saveAndLoadAnEntityWithReferencedEntityById() {

		template.save(legoSet);

		assertThat(legoSet.manual.id).describedAs("id of stored manual").isNotNull();

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		assertThat(reloadedLegoSet.manual).isNotNull();

		SoftAssertions softly = new SoftAssertions();

		softly.assertThat(reloadedLegoSet.manual.getId()) //
				.isEqualTo(legoSet.getManual().getId()) //
				.isNotNull();
		softly.assertThat(reloadedLegoSet.manual.getContent()).isEqualTo(legoSet.getManual().getContent());

		softly.assertAll();
	}

	@Test // DATAJDBC-112
	public void saveAndLoadManyEntitiesWithReferencedEntity() {

		template.save(legoSet);

		Iterable<LegoSet> reloadedLegoSets = template.findAll(LegoSet.class);

		assertThat(reloadedLegoSets).hasSize(1).extracting("id", "manual.id", "manual.content")
				.contains(tuple(legoSet.getId(), legoSet.getManual().getId(), legoSet.getManual().getContent()));
	}

	@Test // DATAJDBC-112
	public void saveAndLoadManyEntitiesByIdWithReferencedEntity() {

		template.save(legoSet);

		Iterable<LegoSet> reloadedLegoSets = template.findAllById(singletonList(legoSet.getId()), LegoSet.class);

		assertThat(reloadedLegoSets).hasSize(1).extracting("id", "manual.id", "manual.content")
				.contains(tuple(legoSet.getId(), legoSet.getManual().getId(), legoSet.getManual().getContent()));
	}

	@Test // DATAJDBC-112
	public void saveAndLoadAnEntityWithReferencedNullEntity() {

		legoSet.setManual(null);

		template.save(legoSet);

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		assertThat(reloadedLegoSet.manual).isNull();
	}

	@Test // DATAJDBC-112
	public void saveAndDeleteAnEntityWithReferencedEntity() {

		template.save(legoSet);

		template.delete(legoSet, LegoSet.class);

		SoftAssertions softly = new SoftAssertions();

		softly.assertThat(template.findAll(LegoSet.class)).isEmpty();
		softly.assertThat(template.findAll(Manual.class)).isEmpty();

		softly.assertAll();
	}

	@Test // DATAJDBC-112
	public void saveAndDeleteAllWithReferencedEntity() {

		template.save(legoSet);

		template.deleteAll(LegoSet.class);

		SoftAssertions softly = new SoftAssertions();

		assertThat(template.findAll(LegoSet.class)).isEmpty();
		assertThat(template.findAll(Manual.class)).isEmpty();

		softly.assertAll();
	}

	@Test // DATAJDBC-112
	@IfProfileValue(name = "current.database.is.not.mssql", value = "true") // DATAJDBC-278
	public void updateReferencedEntityFromNull() {

		legoSet.setManual(null);
		template.save(legoSet);

		Manual manual = new Manual();
		manual.setId(23L);
		manual.setContent("Some content");
		legoSet.setManual(manual);

		template.save(legoSet);

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		assertThat(reloadedLegoSet.manual.content).isEqualTo("Some content");
	}

	@Test // DATAJDBC-112
	public void updateReferencedEntityToNull() {

		template.save(legoSet);

		legoSet.setManual(null);

		template.save(legoSet);

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		SoftAssertions softly = new SoftAssertions();

		softly.assertThat(reloadedLegoSet.manual).isNull();
		softly.assertThat(template.findAll(Manual.class)).describedAs("Manuals failed to delete").isEmpty();

		softly.assertAll();
	}

	@Test // DATAJDBC-112
	public void replaceReferencedEntity() {

		template.save(legoSet);

		Manual manual = new Manual();
		manual.setContent("other content");
		legoSet.setManual(manual);

		template.save(legoSet);

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		SoftAssertions softly = new SoftAssertions();

		softly.assertThat(reloadedLegoSet.manual.content).isEqualTo("other content");
		softly.assertThat(template.findAll(Manual.class)).describedAs("There should be only one manual").hasSize(1);

		softly.assertAll();
	}

	@Test // DATAJDBC-112
	@IfProfileValue(name = "current.database.is.not.mssql", value = "true") // DATAJDBC-278
	public void changeReferencedEntity() {

		template.save(legoSet);

		legoSet.manual.setContent("new content");

		template.save(legoSet);

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		assertThat(reloadedLegoSet.manual.content).isEqualTo("new content");
	}

	@Test // DATAJDBC-266
	public void oneToOneChildWithoutId() {

		OneToOneParent parent = new OneToOneParent();

		parent.content = "parent content";
		parent.child = new ChildNoId();
		parent.child.content = "child content";

		template.save(parent);

		OneToOneParent reloaded = template.findById(parent.id, OneToOneParent.class);

		assertThat(reloaded.child.content).isEqualTo("child content");
	}

	@Test // DATAJDBC-266
	public void oneToOneNullChildWithoutId() {

		OneToOneParent parent = new OneToOneParent();

		parent.content = "parent content";
		parent.child = null;

		template.save(parent);

		OneToOneParent reloaded = template.findById(parent.id, OneToOneParent.class);

		assertThat(reloaded.child).isNull();
	}

	@Test // DATAJDBC-266
	public void oneToOneNullAttributes() {

		OneToOneParent parent = new OneToOneParent();

		parent.content = "parent content";
		parent.child = new ChildNoId();

		template.save(parent);

		OneToOneParent reloaded = template.findById(parent.id, OneToOneParent.class);

		assertThat(reloaded.child).isNotNull();
	}

	@Test // DATAJDBC-125
	public void saveAndLoadAnEntityWithSecondaryReferenceNull() {

		template.save(legoSet);

		assertThat(legoSet.manual.id).describedAs("id of stored manual").isNotNull();

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		assertThat(reloadedLegoSet.alternativeInstructions).isNull();
	}

	@Test // DATAJDBC-125
	public void saveAndLoadAnEntityWithSecondaryReferenceNotNull() {

		legoSet.alternativeInstructions = new Manual();
		legoSet.alternativeInstructions.content = "alternative content";
		template.save(legoSet);

		assertThat(legoSet.manual.id).describedAs("id of stored manual").isNotNull();

		LegoSet reloadedLegoSet = template.findById(legoSet.getId(), LegoSet.class);

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(reloadedLegoSet.alternativeInstructions).isNotNull();
		softly.assertThat(reloadedLegoSet.alternativeInstructions.id).isNotNull();
		softly.assertThat(reloadedLegoSet.alternativeInstructions.id).isNotEqualTo(reloadedLegoSet.manual.id);
		softly.assertThat(reloadedLegoSet.alternativeInstructions.content)
				.isEqualTo(reloadedLegoSet.alternativeInstructions.content);

		softly.assertAll();
	}

	@Test // DATAJDBC-276
	public void saveAndLoadAnEntityWithListOfElementsWithoutId() {

		ListParent entity = new ListParent();
		entity.name = "name";

		ElementNoId element = new ElementNoId();
		element.content = "content";

		entity.content.add(element);

		template.save(entity);

		ListParent reloaded = template.findById(entity.id, ListParent.class);

		assertThat(reloaded.content).extracting(e -> e.content).containsExactly("content");
	}

	@Test // DATAJDBC-259
	public void saveAndLoadAnEntityWithArray() {

		// MySQL and other do not support array datatypes. See
		// https://dev.mysql.com/doc/refman/8.0/en/data-type-overview.html
		assumeNot("mysql");
		assumeNot("mariadb");
		assumeNot("mssql");

		ArrayOwner arrayOwner = new ArrayOwner();
		arrayOwner.digits = new String[] { "one", "two", "three" };

		ArrayOwner saved = template.save(arrayOwner);

		assertThat(saved.id).isNotNull();

		ArrayOwner reloaded = template.findById(saved.id, ArrayOwner.class);

		assertThat(reloaded).isNotNull();
		assertThat(reloaded.id).isEqualTo(saved.id);
		assertThat(reloaded.digits).isEqualTo(new String[] { "one", "two", "three" });
	}

	@Test // DATAJDBC-259
	public void saveAndLoadAnEntityWithMultidimensionalArray() {

		// MySQL and other do not support array datatypes. See
		// https://dev.mysql.com/doc/refman/8.0/en/data-type-overview.html
		assumeNot("mysql");
		assumeNot("mariadb");
		assumeNot("mssql");
		assumeNot("hsqldb");

		ArrayOwner arrayOwner = new ArrayOwner();
		arrayOwner.multidimensional = new String[][] { { "one-a", "two-a", "three-a" }, { "one-b", "two-b", "three-b" } };

		ArrayOwner saved = template.save(arrayOwner);

		assertThat(saved.id).isNotNull();

		ArrayOwner reloaded = template.findById(saved.id, ArrayOwner.class);

		assertThat(reloaded).isNotNull();
		assertThat(reloaded.id).isEqualTo(saved.id);
		assertThat(reloaded.multidimensional)
				.isEqualTo(new String[][] { { "one-a", "two-a", "three-a" }, { "one-b", "two-b", "three-b" } });
	}

	@Test // DATAJDBC-259
	public void saveAndLoadAnEntityWithList() {

		// MySQL and others do not support array datatypes. See
		// https://dev.mysql.com/doc/refman/8.0/en/data-type-overview.html
		assumeNot("mysql");
		assumeNot("mariadb");
		assumeNot("mssql");

		ListOwner arrayOwner = new ListOwner();
		arrayOwner.digits.addAll(Arrays.asList("one", "two", "three"));

		ListOwner saved = template.save(arrayOwner);

		assertThat(saved.id).isNotNull();

		ListOwner reloaded = template.findById(saved.id, ListOwner.class);

		assertThat(reloaded).isNotNull();
		assertThat(reloaded.id).isEqualTo(saved.id);
		assertThat(reloaded.digits).isEqualTo(Arrays.asList("one", "two", "three"));
	}

	@Test // DATAJDBC-259
	public void saveAndLoadAnEntityWithSet() {

		// MySQL and others do not support array datatypes. See
		// https://dev.mysql.com/doc/refman/8.0/en/data-type-overview.html
		assumeNot("mysql");
		assumeNot("mariadb");
		assumeNot("mssql");

		SetOwner setOwner = new SetOwner();
		setOwner.digits.addAll(Arrays.asList("one", "two", "three"));

		SetOwner saved = template.save(setOwner);

		assertThat(saved.id).isNotNull();

		SetOwner reloaded = template.findById(saved.id, SetOwner.class);

		assertThat(reloaded).isNotNull();
		assertThat(reloaded.id).isEqualTo(saved.id);
		assertThat(reloaded.digits).isEqualTo(new HashSet<>(Arrays.asList("one", "two", "three")));
	}

	@Test // DATAJDBC-327
	public void saveAndLoadAnEntityWithByteArray() {
		ByteArrayOwner owner = new ByteArrayOwner();
		owner.binaryData = new byte[] { 1, 23, 42 };

		ByteArrayOwner saved = template.save(owner);

		ByteArrayOwner reloaded = template.findById(saved.id, ByteArrayOwner.class);

		assertThat(reloaded).isNotNull();
		assertThat(reloaded.id).isEqualTo(saved.id);
		assertThat(reloaded.binaryData).isEqualTo(new byte[] { 1, 23, 42 });
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithLongVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithLongVersion(), Number::longValue);
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithPrimitiveLongVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithPrimitiveLongVersion(), Number::longValue);
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithIntegerVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithIntegerVersion(), Number::intValue);
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithPrimitiveIntegerVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithPrimitiveIntegerVersion(), Number::intValue);
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithShortVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithShortVersion(), Number::shortValue);
	}

	@Test // DATAJDBC-219
	public void saveAndUpdateAggregateWithPrimitiveShortVersion() {
		saveAndUpdateAggregateWithVersion(new AggregateWithPrimitiveShortVersion(), Number::shortValue);
	}

	private <T extends Number> void saveAndUpdateAggregateWithVersion(VersionedAggregate aggregate, Function<Number, T> toConcreteNumber) {

		template.save(aggregate);

		VersionedAggregate reloadedAggregate = template.findById(aggregate.getId(), aggregate.getClass());
		assertThat(reloadedAggregate.getVersion()).isEqualTo(toConcreteNumber.apply(1))
				.withFailMessage("version field should initially have the value 1");
		template.save(reloadedAggregate);

		VersionedAggregate updatedAggregate = template.findById(aggregate.getId(), aggregate.getClass());
		assertThat(updatedAggregate.getVersion()).isEqualTo(toConcreteNumber.apply(2))
				.withFailMessage("version field should increment by one with each save");

		reloadedAggregate.setVersion(toConcreteNumber.apply(1));
		assertThatThrownBy(() -> template.save(reloadedAggregate))
				.hasRootCauseInstanceOf(OptimisticLockingFailureException.class)
				.withFailMessage("saving an aggregate with an outdated version should raise an exception");

		reloadedAggregate.setVersion(toConcreteNumber.apply(3));
		assertThatThrownBy(() -> template.save(reloadedAggregate))
				.hasRootCauseInstanceOf(OptimisticLockingFailureException.class)
				.withFailMessage("saving an aggregate with a future version should raise an exception");
	}

	private static void assumeNot(String dbProfileName) {

		Assume.assumeTrue("true"
				.equalsIgnoreCase(ProfileValueUtils.retrieveProfileValueSource(JdbcAggregateTemplateIntegrationTests.class)
						.get("current.database.is.not." + dbProfileName)));
	}

	private static class ArrayOwner {
		@Id Long id;

		String[] digits;
		String[][] multidimensional;
	}

	private static class ByteArrayOwner {
		@Id Long id;

		byte[] binaryData;
	}

	@Table("ARRAY_OWNER")
	private static class ListOwner {
		@Id Long id;

		List<String> digits = new ArrayList<>();
	}

	@Table("ARRAY_OWNER")
	private static class SetOwner {
		@Id Long id;

		Set<String> digits = new HashSet<>();
	}

	private static LegoSet createLegoSet() {

		LegoSet entity = new LegoSet();
		entity.setName("Star Destroyer");

		Manual manual = new Manual();
		manual.setContent("Accelerates to 99% of light speed. Destroys almost everything. See https://what-if.xkcd.com/1/");
		entity.setManual(manual);

		return entity;
	}

	@Data
	static class LegoSet {

		@Column("id1") @Id private Long id;

		private String name;

		private Manual manual;
		@Column("alternative") private Manual alternativeInstructions;
	}

	@Data
	static class Manual {

		@Column("id2") @Id private Long id;
		private String content;

	}

	static class OneToOneParent {

		@Column("id3") @Id private Long id;
		private String content;

		private ChildNoId child;
	}

	static class ChildNoId {
		private String content;
	}

	static class ListParent {

		@Column("id4") @Id private Long id;
		String name;
		List<ElementNoId> content = new ArrayList<>();
	}

	static class ElementNoId {
		private String content;
	}

	@Data
	static abstract class VersionedAggregate {

		@Id private Long id;

		abstract Number getVersion();

		abstract void setVersion(Number newVersion);
	}

	@Data
	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithLongVersion extends VersionedAggregate {

		@Version private Long version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (Long) newVersion;
		}
	}

	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithPrimitiveLongVersion extends VersionedAggregate {

		@Version private long version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (long) newVersion;
		}

		@Override
		Number getVersion(){
			return this.version;
		}
	}

	@Data
	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithIntegerVersion extends VersionedAggregate {

		@Version private Integer version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (Integer) newVersion;
		}
	}

	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithPrimitiveIntegerVersion extends VersionedAggregate {

		@Version private int version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (int) newVersion;
		}

		@Override
		Number getVersion(){
			return this.version;
		}
	}

	@Data
	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithShortVersion extends VersionedAggregate {

		@Version private Short version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (Short) newVersion;
		}
	}

	@Table("VERSIONED_AGGREGATE")
	static class AggregateWithPrimitiveShortVersion extends VersionedAggregate {

		@Version
		private short version;

		@Override
		void setVersion(Number newVersion) {
			this.version = (short) newVersion;
		}

		@Override
		Number getVersion(){
			return this.version;
		}

	}


	@Configuration
	@Import(TestConfiguration.class)
	static class Config {

		@Bean
		Class<?> testClass() {
			return JdbcAggregateTemplateIntegrationTests.class;
		}

		@Bean
		JdbcAggregateOperations operations(ApplicationEventPublisher publisher, RelationalMappingContext context,
				DataAccessStrategy dataAccessStrategy, RelationalConverter converter) {
			return new JdbcAggregateTemplate(publisher, context, converter, dataAccessStrategy);
		}
	}
}
