package com.smartomni.jobs;

import org.quartz.JobBuilder;
import org.quartz.DateBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobScheduleConfiguration {
    @Bean
    JobDetail pollOrdersJob(@Value("${smartomni.jobs.order-url}") String url) {
        return job("poll-orders", url);
    }

    @Bean
    Trigger pollOrdersTrigger(JobDetail pollOrdersJob) {
        return trigger("poll-orders", pollOrdersJob, 180);
    }

    @Bean
    JobDetail syncPricesJob(@Value("${smartomni.jobs.integration-url}") String url) {
        return job("sync-prices", url);
    }

    @Bean
    Trigger syncPricesTrigger(JobDetail syncPricesJob) {
        return trigger("sync-prices", syncPricesJob, 900);
    }

    @Bean
    JobDetail publishOutboxJob(@Value("${smartomni.jobs.inventory-url}") String url) {
        return job("publish-outbox", url);
    }

    @Bean
    Trigger publishOutboxTrigger(JobDetail publishOutboxJob) {
        return trigger("publish-outbox", publishOutboxJob, 30);
    }

    private JobDetail job(String name, String url) {
        return JobBuilder.newJob(InternalHttpJob.class)
                .withIdentity(name)
                .usingJobData("url", url)
                .storeDurably()
                .build();
    }

    private Trigger trigger(String name, JobDetail job, int seconds) {
        return TriggerBuilder.newTrigger()
                .withIdentity(name + "-trigger")
                .forJob(job)
                .startAt(DateBuilder.futureDate(seconds, DateBuilder.IntervalUnit.SECOND))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }
}
