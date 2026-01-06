import React, { useState, useEffect } from 'react';

/**
 * UUIDv4のサンプルを表示するコンポーネント
 * 毎回ランダムなUUIDv4を生成して表示する
 */
export function UUIDV4(): JSX.Element {
    const [uuid, setUuid] = useState<string>('');

    useEffect(() => {
        // クライアントサイドでのみ実行
        setUuid(crypto.randomUUID());
    }, []);

    return (
        <code style={{
            fontFamily: 'monospace',
            fontSize: '0.85em',
            backgroundColor: 'var(--ifm-code-background)',
            padding: '2px 4px',
            borderRadius: '3px'
        }}>
            {uuid || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'}
        </code>
    );
}

/**
 * UUIDv7のサンプルを表示するコンポーネント
 * UUIDv7は時間ベースのため、現在時刻を元に生成される形式を表示
 */
export function UUIDV7(): JSX.Element {
    const [uuid, setUuid] = useState<string>('');

    useEffect(() => {
        // UUIDv7形式のサンプルを生成
        // 実際のUUIDv7は時間ベースだが、ここではサンプル表示用
        const timestamp = Date.now().toString(16).padStart(12, '0');
        const random = Array.from({ length: 4 }, () =>
            Math.floor(Math.random() * 65536).toString(16).padStart(4, '0')
        ).join('');
        // UUIDv7形式: xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx
        const v7 = `${timestamp.slice(0, 8)}-${timestamp.slice(8, 12)}-7${random.slice(0, 3)}-${['8', '9', 'a', 'b'][Math.floor(Math.random() * 4)]}${random.slice(4, 7)}-${random.slice(7, 19).padEnd(12, '0')}`;
        setUuid(v7);
    }, []);

    return (
        <code style={{
            fontFamily: 'monospace',
            fontSize: '0.85em',
            backgroundColor: 'var(--ifm-code-background)',
            padding: '2px 4px',
            borderRadius: '3px'
        }}>
            {uuid || '01234567-89ab-7cde-8f01-234567890abc'}
        </code>
    );
}
