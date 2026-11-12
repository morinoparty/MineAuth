import { decode } from "blurhash";
import { useEffect, useRef, useState } from "react";
import { css } from "styled-system/css";
import { type CoverImage, coverUrl, loadCover } from "../cover";

// blurhash は低解像度で展開して CSS で引き伸ばす
const PLACEHOLDER_WIDTH = 32;
const PLACEHOLDER_HEIGHT = 18;

const fillPanel = css({ position: "absolute", inset: "0", width: "full", height: "full", objectFit: "cover" });

const DEFAULT_POSITION = "center";

interface BlurhashCanvasProps {
  hash: string;
  position: string;
}

/** 画像の読み込みが終わるまで表示するプレースホルダ */
const BlurhashCanvas = ({ hash, position }: BlurhashCanvasProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const context = canvasRef.current?.getContext("2d");
    if (!context) return;
    try {
      const pixels = decode(hash, PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT);
      const imageData = context.createImageData(PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT);
      imageData.data.set(pixels);
      context.putImageData(imageData, 0, 0);
    } catch {
      // 不正な blurhash でも背景のグラデーションが見えるだけなので、画面は止めない
    }
  }, [hash]);

  return (
    <canvas ref={canvasRef} width={PLACEHOLDER_WIDTH} height={PLACEHOLDER_HEIGHT} className={fillPanel} style={{ objectPosition: position }} />
  );
};

/** 右側のカバー画像。グラデーションの上に blurhash、読み込めたら本画像をフェードインで重ねる */
export const CoverPanel = () => {
  const [cover, setCover] = useState<CoverImage | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    loadCover().then((image) => {
      if (active) setCover(image);
    });
    return () => {
      active = false;
    };
  }, []);

  return (
    <div
      className={css({
        position: "relative",
        height: "full",
        overflow: "hidden",
        bg: "colorPalette.3",
        backgroundImage: "linear-gradient(160deg, var(--ma-colors-color-palette-4), var(--ma-colors-color-palette-7))",
      })}
    >
      {cover && !failed && (
        <>
          <BlurhashCanvas hash={cover.blurhash} position={cover.position ?? DEFAULT_POSITION} />
          <img
            src={coverUrl(cover)}
            alt=""
            width={cover.width}
            height={cover.height}
            onLoad={() => setLoaded(true)}
            onError={() => setFailed(true)}
            className={`${fillPanel} ${css({ transition: "opacity 0.4s ease" })}`}
            style={{ opacity: loaded ? 1 : 0, objectPosition: cover.position ?? DEFAULT_POSITION }}
          />
        </>
      )}
    </div>
  );
};
