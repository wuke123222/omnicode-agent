<script setup lang="ts">
import { computed, ref, watch } from 'vue';

type ImageStatus = 'empty' | 'loading' | 'ready' | 'error';

interface GeneratedViewLabels {
    loading: string;
    emptyTitle: string;
    emptyDescription: string;
    errorTitle: string;
    errorDescription: string;
    retry: string;
}

interface GeneratedViewProps {
    /** Public URL or imported asset URL. Defaults to the supplied reference image filename. */
    imageSrc?: string;
    /** Pass an empty string only when the image is intentionally decorative. */
    imageAlt?: string;
    /** Optional compatibility hook for callers migrating from the React component. */
    className?: string;
    labels?: Partial<GeneratedViewLabels>;
    retryDisabled?: boolean;
}

const DEFAULT_IMAGE_SRC = '/f27df5bd06c0fa7bd6f5e5328cc193db.jpeg';
const DEFAULT_IMAGE_ALT = '从飞机舷窗俯瞰云层与雪山，银色机翼横贯画面下方';

const DEFAULT_LABELS: GeneratedViewLabels = {
    loading: '正在加载云端景色',
    emptyTitle: '暂无景色',
    emptyDescription: '请提供一张可显示的图片。',
    errorTitle: '图片加载失败',
    errorDescription: '请检查图片资源后重试。',
    retry: '重新加载',
};

const props = defineProps<GeneratedViewProps>();

const emit = defineEmits<{
    (event: 'image-load', imageEvent: Event): void;
    (event: 'image-error', imageEvent: Event): void;
    (event: 'retry'): void;
}>();

const normalizedSrc = computed(() => (props.imageSrc ?? DEFAULT_IMAGE_SRC).trim());
const resolvedAlt = computed(() => props.imageAlt ?? DEFAULT_IMAGE_ALT);
const labels = computed<GeneratedViewLabels>(() => ({
    loading: props.labels?.loading ?? DEFAULT_LABELS.loading,
    emptyTitle: props.labels?.emptyTitle ?? DEFAULT_LABELS.emptyTitle,
    emptyDescription: props.labels?.emptyDescription ?? DEFAULT_LABELS.emptyDescription,
    errorTitle: props.labels?.errorTitle ?? DEFAULT_LABELS.errorTitle,
    errorDescription: props.labels?.errorDescription ?? DEFAULT_LABELS.errorDescription,
    retry: props.labels?.retry ?? DEFAULT_LABELS.retry,
}));

const status = ref<ImageStatus>(normalizedSrc.value ? 'loading' : 'empty');
const reloadKey = ref(0);
const imageKey = computed(() => `${normalizedSrc.value}:${reloadKey.value}`);

watch(normalizedSrc, (source) => {
    status.value = source ? 'loading' : 'empty';
    reloadKey.value += 1;
});

function isCurrentImageEvent(event: Event): boolean {
    const image = event.currentTarget as HTMLImageElement | null;
    return image?.dataset.source === normalizedSrc.value;
}

function handleImageLoad(event: Event) {
    if (!isCurrentImageEvent(event)) {
        return;
    }

    status.value = 'ready';
    emit('image-load', event);
}

function handleImageError(event: Event) {
    if (!isCurrentImageEvent(event)) {
        return;
    }

    status.value = 'error';
    emit('image-error', event);
}

function handleRetry() {
    if (props.retryDisabled || !normalizedSrc.value) {
        return;
    }

    status.value = 'loading';
    reloadKey.value += 1;
    emit('retry');
}
</script>

<template>
    <div :class="[$style.root, props.className]">
        <div :class="$style.content">
            <figure
                :class="$style.frame"
                :data-image-state="status"
                :aria-busy="status === 'loading'"
            >
                <img
                    v-if="normalizedSrc"
                    :key="imageKey"
                    :class="[$style.image, status === 'ready' && $style.imageVisible]"
                    :src="normalizedSrc"
                    :data-source="normalizedSrc"
                    :alt="resolvedAlt"
                    loading="eager"
                    decoding="async"
                    :draggable="false"
                    :aria-hidden="status !== 'ready'"
                    @load="handleImageLoad"
                    @error="handleImageError"
                />

                <div
                    v-if="status !== 'ready'"
                    :class="$style.stateLayer"
                    :role="status === 'error' ? 'alert' : 'status'"
                    :aria-live="status === 'error' ? 'assertive' : 'polite'"
                >
                    <div v-if="status === 'loading'" :class="$style.stateStack">
                        <span :class="$style.spinner" aria-hidden="true" />
                        <p :class="$style.stateText">{{ labels.loading }}</p>
                    </div>

                    <div v-else-if="status === 'empty'" :class="$style.stateStack">
                        <h2 :class="$style.stateTitle">{{ labels.emptyTitle }}</h2>
                        <p :class="$style.stateText">{{ labels.emptyDescription }}</p>
                    </div>

                    <div v-else :class="$style.stateStack">
                        <h2 :class="$style.stateTitle">{{ labels.errorTitle }}</h2>
                        <p :class="$style.stateText">{{ labels.errorDescription }}</p>
                        <button
                            :class="$style.retryButton"
                            type="button"
                            :disabled="props.retryDisabled"
                            @click="handleRetry"
                        >
                            {{ labels.retry }}
                        </button>
                    </div>
                </div>
            </figure>
        </div>
    </div>
</template>

<style module src="./GeneratedView.module.css"></style>
