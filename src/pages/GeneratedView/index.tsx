import { type CSSProperties, useId, useMemo, useState } from 'react';
import { Avatar, Button, Card, Layout, Spin, Typography } from '@douyinfe/semi-ui-19';
import {
    IconChevronDown,
    IconHome,
    IconSearch,
    IconUser,
} from '@douyinfe/semi-icons';

import styles from './index.module.css';

const { Content } = Layout;
const { Text } = Typography;

export type GeneratedViewStatus = 'ready' | 'loading' | 'empty' | 'error';
export type FeedKey = 'discover' | 'you';
export type RoomCategory = 'Chat' | 'Music' | 'Friends';
export type RoomFilter = 'All' | RoomCategory;
export type NavigationKey = 'room' | 'explore' | 'messages' | 'me';

export interface VoiceRoom {
    id: string;
    title: string;
    category: RoomCategory;
    coverSrc: string;
    hostName: string;
    listeners: string;
    participantInitials: readonly string[];
    accent: string;
    coverPosition?: string;
}

export interface GeneratedViewProps {
    rooms?: readonly VoiceRoom[];
    status?: GeneratedViewStatus;
    disabled?: boolean;
    className?: string;
    initialFeed?: FeedKey;
    initialFilter?: RoomFilter;
    initialNavigation?: NavigationKey;
    messageCount?: number;
    showDeviceStatus?: boolean;
    errorMessage?: string;
    onFeedChange?: (feed: FeedKey) => void;
    onFilterChange?: (filter: RoomFilter) => void;
    onRoomSelect?: (room: VoiceRoom) => void;
    onVoiceMatchOpen?: () => void;
    onLeaderboardOpen?: () => void;
    onSearchOpen?: () => void;
    onHomeOpen?: () => void;
    onRegionOpen?: () => void;
    onNavigationChange?: (item: NavigationKey) => void;
    onRetry?: () => void;
}

const FILTERS: readonly RoomFilter[] = ['All', 'Chat', 'Music', 'Friends'];

const DEFAULT_ROOMS: readonly VoiceRoom[] = [
    {
        id: 'truth-or-dare',
        title: 'Truth or Dare 😈',
        category: 'Chat',
        coverSrc: 'https://images.unsplash.com/photo-1524504388940-b1c1722653e1?auto=format&fit=crop&w=720&q=85',
        hostName: 'Luna',
        listeners: '223.2w',
        participantInitials: ['L', 'M', 'A', 'K'],
        accent: '#f24ac8',
        coverPosition: 'center 28%',
    },
    {
        id: 'midnight-therapy',
        title: 'Midnight Therapy',
        category: 'Chat',
        coverSrc: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=720&q=85',
        hostName: 'Mia',
        listeners: '223.2w',
        participantInitials: ['M', 'J', 'S', 'R'],
        accent: '#ff4fc8',
        coverPosition: 'center 22%',
    },
    {
        id: 'talk-till-sunrise',
        title: 'Talk Till Sunrise',
        category: 'Friends',
        coverSrc: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?auto=format&fit=crop&w=720&q=85',
        hostName: 'Noah',
        listeners: '223.2w',
        participantInitials: ['N', 'E', 'R', 'T'],
        accent: '#fb3d93',
        coverPosition: 'center 20%',
    },
    {
        id: 'kiss-or-pass',
        title: 'Kiss or Pass 💕',
        category: 'Music',
        coverSrc: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=720&q=85',
        hostName: 'Ivy',
        listeners: '223.2w',
        participantInitials: ['I', 'C', 'V', 'B'],
        accent: '#df4dff',
        coverPosition: 'center 20%',
    },
    {
        id: 'velvet-confessions',
        title: 'Velvet Confessions',
        category: 'Chat',
        coverSrc: 'https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=720&q=85',
        hostName: 'Aria',
        listeners: '116.8w',
        participantInitials: ['A', 'H', 'P'],
        accent: '#ff55d6',
        coverPosition: 'center 18%',
    },
    {
        id: 'moonlit-whispers',
        title: 'Moonlit Whispers',
        category: 'Chat',
        coverSrc: 'https://images.unsplash.com/photo-1488426862026-3ee34a7d66df?auto=format&fit=crop&w=720&q=85',
        hostName: 'Sora',
        listeners: '98.6w',
        participantInitials: ['S', 'Y', 'D'],
        accent: '#b96cff',
        coverPosition: 'center 24%',
    },
];

const NAVIGATION_ITEMS: readonly { key: NavigationKey; label: string }[] = [
    { key: 'room', label: 'Room' },
    { key: 'explore', label: 'Explore' },
    { key: 'messages', label: 'Messages' },
    { key: 'me', label: 'Me' },
];

function DeviceStatusBar() {
    return (
        <div className={styles.deviceStatus} aria-hidden="true">
            <time dateTime="09:41">9:41</time>
            <span className={styles.deviceIndicators}>
                <span className={styles.cellSignal}>
                    <i />
                    <i />
                    <i />
                    <i />
                </span>
                <span className={styles.wifiSignal} />
                <span className={styles.battery}><i /></span>
            </span>
        </div>
    );
}

interface VoiceMatchBannerProps {
    disabled: boolean;
    onOpen?: () => void;
}

function VoiceMatchBanner({ disabled, onOpen }: VoiceMatchBannerProps) {
    return (
        <Card className={styles.matchBanner}>
            <div className={styles.bannerStars} aria-hidden="true" />
            <div className={styles.bannerCopy}>
                <span className={styles.voiceOrb} aria-hidden="true">
                    <span className={styles.equalizer}>
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                    </span>
                </span>
                <strong className={styles.bannerTitle}>VOICE MATCH</strong>
                <Button
                    className={styles.bannerCta}
                    theme="solid"
                    type="primary"
                    size="small"
                    htmlType="button"
                    disabled={disabled}
                    onClick={onOpen}
                >
                    Meet Your Vibe <span aria-hidden="true">›</span>
                </Button>
            </div>
            <div className={styles.bannerArtwork} aria-hidden="true">
                <span className={styles.neonHeart}>♡</span>
                <span className={`${styles.personSilhouette} ${styles.personLeft}`} />
                <span className={`${styles.personSilhouette} ${styles.personRight}`} />
            </div>
        </Card>
    );
}

function CategoryMark({ category }: { category: RoomCategory }) {
    const glyph = category === 'Music' ? '♫' : category === 'Friends' ? '♧' : '◡';
    return <span aria-hidden="true">{glyph}</span>;
}

interface RoomCardProps {
    room: VoiceRoom;
    disabled: boolean;
    priority: boolean;
    onSelect?: (room: VoiceRoom) => void;
}

function RoomCard({ room, disabled, priority, onSelect }: RoomCardProps) {
    return (
        <Card
            className={styles.roomCard}
            style={{ '--room-accent': room.accent } as CSSProperties}
        >
            <button
                className={styles.roomButton}
                type="button"
                disabled={disabled}
                aria-label={`Open ${room.title}, hosted by ${room.hostName}`}
                onClick={() => onSelect?.(room)}
            >
                <span className={styles.roomArtwork} aria-hidden="true">
                    <img
                        className={styles.roomCover}
                        src={room.coverSrc}
                        alt=""
                        loading={priority ? 'eager' : 'lazy'}
                        decoding="async"
                        referrerPolicy="no-referrer"
                        style={{ objectPosition: room.coverPosition ?? 'center' }}
                        onError={(event) => {
                            event.currentTarget.hidden = true;
                        }}
                    />
                    <span className={styles.artworkGlow} />
                </span>

                <span
                    className={styles.categoryBadge}
                    data-category={room.category.toLowerCase()}
                >
                    <CategoryMark category={room.category} />
                    {room.category}
                </span>

                <span className={styles.roomMeta}>
                    <span className={styles.hostOrb} aria-hidden="true">
                        <img
                            src={room.coverSrc}
                            alt=""
                            loading="lazy"
                            decoding="async"
                            referrerPolicy="no-referrer"
                            onError={(event) => {
                                event.currentTarget.hidden = true;
                            }}
                        />
                        <span className={styles.equalizer}>
                            <i />
                            <i />
                            <i />
                            <i />
                            <i />
                        </span>
                    </span>

                    <span className={styles.participantStack} aria-label={`${room.participantInitials.length} participants`}>
                        {room.participantInitials.map((initial, index) => (
                            <Avatar
                                key={`${room.id}-${initial}-${index}`}
                                className={styles.participantAvatar}
                                size="small"
                            >
                                {initial}
                            </Avatar>
                        ))}
                    </span>

                    <span className={styles.listenerCount}>
                        <span className={styles.listenerBars} aria-hidden="true">
                            <i />
                            <i />
                            <i />
                            <i />
                        </span>
                        {room.listeners}
                    </span>
                    <strong className={styles.roomTitle}>{room.title}</strong>
                </span>
            </button>
        </Card>
    );
}

interface StatusPanelProps {
    status: Exclude<GeneratedViewStatus, 'ready'>;
    disabled: boolean;
    errorMessage: string;
    onRetry?: () => void;
}

function StatusPanel({ status, disabled, errorMessage, onRetry }: StatusPanelProps) {
    if (status === 'loading') {
        return (
            <section className={styles.statusPanel} role="status" aria-live="polite">
                <Spin size="large" />
                <strong>Finding live rooms…</strong>
                <Text className={styles.statusDescription}>Matching voices and vibes for you.</Text>
            </section>
        );
    }

    if (status === 'empty') {
        return (
            <section className={styles.statusPanel} role="status" aria-live="polite">
                <span className={styles.emptyOrb} aria-hidden="true">✦</span>
                <strong>No rooms are live yet</strong>
                <Text className={styles.statusDescription}>Check back soon for new conversations.</Text>
            </section>
        );
    }

    return (
        <section className={styles.statusPanel} role="alert">
            <span className={styles.emptyOrb} aria-hidden="true">!</span>
            <strong>Couldn’t load rooms</strong>
            <Text className={styles.statusDescription}>{errorMessage}</Text>
            <Button
                theme="solid"
                type="primary"
                htmlType="button"
                disabled={disabled || !onRetry}
                onClick={onRetry}
            >
                Try again
            </Button>
        </section>
    );
}

function NavigationIcon({ item }: { item: NavigationKey }) {
    if (item === 'room') {
        return <IconHome aria-hidden="true" />;
    }
    if (item === 'me') {
        return <IconUser aria-hidden="true" />;
    }
    if (item === 'messages') {
        return <span className={styles.messageIcon} aria-hidden="true" />;
    }
    return <span className={styles.exploreIcon} aria-hidden="true" />;
}

interface BottomNavigationProps {
    activeItem: NavigationKey;
    disabled: boolean;
    messageCount: number;
    onChange: (item: NavigationKey) => void;
}

function BottomNavigation({
    activeItem,
    disabled,
    messageCount,
    onChange,
}: BottomNavigationProps) {
    const messageLabel = messageCount > 99 ? '99+' : String(Math.max(0, messageCount));

    return (
        <nav className={styles.bottomNavigation} aria-label="Primary navigation">
            {NAVIGATION_ITEMS.map((item) => {
                const active = item.key === activeItem;
                return (
                    <button
                        key={item.key}
                        className={`${styles.bottomNavItem} ${active ? styles.bottomNavItemActive : ''}`}
                        type="button"
                        disabled={disabled}
                        aria-current={active ? 'page' : undefined}
                        onClick={() => onChange(item.key)}
                    >
                        <span className={styles.bottomNavIcon}>
                            <NavigationIcon item={item.key} />
                            {item.key === 'messages' && messageCount > 0 && (
                                <span className={styles.messageBadge} aria-label={`${messageCount} unread messages`}>
                                    {messageLabel}
                                </span>
                            )}
                        </span>
                        <span>{item.label}</span>
                    </button>
                );
            })}
        </nav>
    );
}

/**
 * Screenshot-derived mobile voice-room discovery page.
 * All navigation and room actions are callbacks; this component performs no routing or API requests.
 */
export function GeneratedView({
    rooms = DEFAULT_ROOMS,
    status = 'ready',
    disabled = false,
    className,
    initialFeed = 'discover',
    initialFilter = 'All',
    initialNavigation = 'room',
    messageCount = 128,
    showDeviceStatus = true,
    errorMessage = 'Please check your connection and try again.',
    onFeedChange,
    onFilterChange,
    onRoomSelect,
    onVoiceMatchOpen,
    onLeaderboardOpen,
    onSearchOpen,
    onHomeOpen,
    onRegionOpen,
    onNavigationChange,
    onRetry,
}: GeneratedViewProps) {
    const instanceId = useId();
    const [activeFeed, setActiveFeed] = useState<FeedKey>(initialFeed);
    const [activeFilter, setActiveFilter] = useState<RoomFilter>(initialFilter);
    const [activeNavigation, setActiveNavigation] = useState<NavigationKey>(initialNavigation);

    const visibleRooms = useMemo(
        () => activeFilter === 'All'
            ? rooms
            : rooms.filter((room) => room.category === activeFilter),
        [activeFilter, rooms],
    );
    const resolvedStatus = status === 'ready' && rooms.length === 0 ? 'empty' : status;
    const rootClassName = [styles.page, className].filter(Boolean).join(' ');
    const feedPanelId = `${instanceId}-feed-panel`;
    const roomGridId = `${instanceId}-room-grid`;

    const changeFeed = (feed: FeedKey) => {
        if (disabled) return;
        setActiveFeed(feed);
        onFeedChange?.(feed);
    };

    const changeFilter = (filter: RoomFilter) => {
        if (disabled) return;
        setActiveFilter(filter);
        onFilterChange?.(filter);
    };

    const changeNavigation = (item: NavigationKey) => {
        if (disabled) return;
        setActiveNavigation(item);
        onNavigationChange?.(item);
    };

    return (
        <Layout className={rootClassName}>
            <div className={styles.appShell}>
                {showDeviceStatus && <DeviceStatusBar />}

                <header className={styles.topNavigation}>
                    <div className={styles.feedTabs} role="tablist" aria-label="Feed">
                        {(['discover', 'you'] as const).map((feed) => {
                            const active = feed === activeFeed;
                            return (
                                <Button
                                    key={feed}
                                    id={`${instanceId}-feed-${feed}`}
                                    className={`${styles.feedTab} ${active ? styles.feedTabActive : ''}`}
                                    theme="borderless"
                                    type="tertiary"
                                    htmlType="button"
                                    role="tab"
                                    aria-selected={active}
                                    aria-controls={feedPanelId}
                                    disabled={disabled}
                                    onClick={() => changeFeed(feed)}
                                >
                                    {feed === 'discover' ? 'Discover' : 'You'}
                                </Button>
                            );
                        })}
                    </div>

                    <div className={styles.headerActions} role="group" aria-label="Page actions">
                        <Button
                            className={`${styles.iconButton} ${styles.trophyButton}`}
                            theme="borderless"
                            type="tertiary"
                            htmlType="button"
                            disabled={disabled}
                            aria-label="Open leaderboard"
                            onClick={onLeaderboardOpen}
                        >
                            <span aria-hidden="true">🏆</span>
                        </Button>
                        <Button
                            className={styles.iconButton}
                            icon={<IconSearch />}
                            theme="borderless"
                            type="tertiary"
                            htmlType="button"
                            disabled={disabled}
                            aria-label="Search rooms"
                            onClick={onSearchOpen}
                        />
                        <Button
                            className={styles.iconButton}
                            icon={<IconHome />}
                            theme="borderless"
                            type="tertiary"
                            htmlType="button"
                            disabled={disabled}
                            aria-label="Go home"
                            onClick={onHomeOpen}
                        />
                    </div>
                </header>

                <Content
                    id={feedPanelId}
                    className={styles.content}
                    role="tabpanel"
                    aria-labelledby={`${instanceId}-feed-${activeFeed}`}
                >
                    <VoiceMatchBanner disabled={disabled} onOpen={onVoiceMatchOpen} />

                    <div className={styles.filterRow}>
                        <div className={styles.filterScroller} role="tablist" aria-label="Room categories">
                            {FILTERS.map((filter) => {
                                const active = activeFilter === filter;
                                return (
                                    <Button
                                        key={filter}
                                        id={`${instanceId}-filter-${filter.toLowerCase()}`}
                                        className={`${styles.filterTab} ${active ? styles.filterTabActive : ''}`}
                                        theme="borderless"
                                        type="tertiary"
                                        htmlType="button"
                                        role="tab"
                                        aria-selected={active}
                                        aria-controls={roomGridId}
                                        disabled={disabled}
                                        onClick={() => changeFilter(filter)}
                                    >
                                        {filter}
                                    </Button>
                                );
                            })}
                        </div>
                        <Button
                            className={styles.regionButton}
                            theme="borderless"
                            type="tertiary"
                            htmlType="button"
                            disabled={disabled}
                            aria-label="Choose room region"
                            aria-haspopup="menu"
                            onClick={onRegionOpen}
                        >
                            <span className={styles.globeIcon} aria-hidden="true">◉</span>
                            <IconChevronDown aria-hidden="true" />
                        </Button>
                    </div>

                    <main className={styles.main} aria-label="Live voice rooms">
                        {resolvedStatus !== 'ready' ? (
                            <StatusPanel
                                status={resolvedStatus}
                                disabled={disabled}
                                errorMessage={errorMessage}
                                onRetry={onRetry}
                            />
                        ) : visibleRooms.length === 0 ? (
                            <section className={styles.statusPanel} role="status" aria-live="polite">
                                <span className={styles.emptyOrb} aria-hidden="true">✦</span>
                                <strong>No {activeFilter} rooms right now</strong>
                                <Text className={styles.statusDescription}>Try another category.</Text>
                                <Button
                                    theme="solid"
                                    type="primary"
                                    htmlType="button"
                                    disabled={disabled}
                                    onClick={() => changeFilter('All')}
                                >
                                    View all rooms
                                </Button>
                            </section>
                        ) : (
                            <section
                                id={roomGridId}
                                className={styles.roomGrid}
                                role="tabpanel"
                                aria-labelledby={`${instanceId}-filter-${activeFilter.toLowerCase()}`}
                                aria-live="polite"
                            >
                                <h1 className={styles.srOnly}>{activeFeed === 'discover' ? 'Discover' : 'Your'} live rooms</h1>
                                {visibleRooms.map((room, index) => (
                                    <RoomCard
                                        key={room.id}
                                        room={room}
                                        disabled={disabled}
                                        priority={index < 2}
                                        onSelect={onRoomSelect}
                                    />
                                ))}
                            </section>
                        )}
                    </main>
                </Content>

                <BottomNavigation
                    activeItem={activeNavigation}
                    disabled={disabled}
                    messageCount={messageCount}
                    onChange={changeNavigation}
                />
            </div>
        </Layout>
    );
}

export default GeneratedView;
