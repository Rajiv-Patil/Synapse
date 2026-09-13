import * as React from 'react';
import {Button, KIND, SHAPE, SIZE} from 'baseui/button';


interface MagneticButtonProps{
    Label: React.ReactNode;
    RightAppend?: React.ReactNode;
}

export default function MagneticButton({Label, RightAppend}:MagneticButtonProps) {
    //track cursor offset coordinates
    const [position, setPosition] = React.useState({ x: 0, y: 0 });

    // dom ref to measure the button's position
    const buttonRef = React.useRef<HTMLButtonElement>(null);

    const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
        if (!buttonRef.current) return;

        // Calculate central coords of the button
        const rect = buttonRef.current.getBoundingClientRect();
        const centerX = rect.left + rect.width / 2;
        const centerY = rect.top + rect.height / 2;

        const dx = e.clientX - centerX;
        const dy = e.clientY - centerY;
        const distance = Math.hypot(dx, dy);

        //pull distance logic
        if (distance > 15) {
            setPosition({
                x: dx * 0.05,
                y: dy * 0.05,
            });
        }
        else {
            //rest at default position
            setPosition({ x: 0, y: 0 });
        }
    };

    const handleMouseLeave = () => {
        // Snap back on mouse hover out
        setPosition({ x: 0, y: 0 });
    };

    return (
        <div
            onMouseMove={handleMouseMove}
            onMouseLeave={handleMouseLeave}
            style={{ display: 'inline-block', padding: '20px' }}
        >
            <Button
                endEnhancer={RightAppend}
                ref={buttonRef}
                shape={SHAPE.pill}
                kind={KIND.primary}
                size={SIZE.default}
                overrides={{
                    BaseButton: {
                        style: ({}) => ({
                            transform: `translate(${position.x}px, ${position.y}px)`,
                            transition:
                                position.x === 0 && position.y === 0
                                    ? 'transform 0.5s cubic-bezier(0.175, 0.885, 0.32, 1.275)'
                                    : 'transform 0.1s cubic-bezier(0.175, 0.885, 0.32, 1.275)',
                        }),
                    },
                }}
            >
                {Label}
            </Button>
        </div>
    );
}