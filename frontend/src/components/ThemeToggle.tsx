import { Button, KIND, SHAPE, SIZE } from "baseui/button";
import { Icon } from "baseui/icon";
import { styled } from "baseui";
import { useThemeContext } from "../Providers";
import {useState} from "react";

// SVG icon wrappers for Sun and Moon
const Sun = (props: any) => (
    <Icon {...props} viewBox="0 0 24 24">
        <circle cx="12" cy="12" r="5" stroke="currentColor" strokeWidth="2" fill="none" />
        <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" stroke="currentColor" strokeWidth="2" />
    </Icon>
);
const Moon = (props: any) => (
    <Icon {...props} viewBox="0 0 24 24">
        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" stroke="currentColor" strokeWidth="2" fill="none" />
    </Icon>
);

//hover state manager
const IconWrapper = styled('div', {
    position: 'relative',
    width: '18px',
    height: '18px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    pointerEvents: 'none',
});

//manage active hover state ☀️
const ActiveLayer = styled('div', ({ $theme, $isHovered }: { $theme: any; $isHovered: boolean }) => ({
    position: 'absolute',
    display: 'flex',
    opacity: $isHovered ? 0 : 1,
    transform: $isHovered ? 'scale(0.8)' : 'scale(1)',
    transition: `opacity ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}, transform ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}`,
}));

//manage preview on active hover state
const PreviewLayer = styled('div', ({ $theme, $isHovered }: { $theme: any; $isHovered: boolean }) => ({
    position: 'absolute',
    display: 'flex',
    opacity: $isHovered ? 1 : 0,
    transform: $isHovered ? 'scale(1)' : 'scale(0.8)',
    transition: `opacity ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}, transform ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}`,
}));

export default function ThemeToggle() {
    const { currentTheme, toggleTheme } = useThemeContext();
    const isLight = currentTheme === 'light';
    const [isHovered, setIsHovered] = useState(false);
    return (
        <div onMouseEnter={() => setIsHovered(true)}
             onMouseLeave={() => setIsHovered(false)}
             style={{
                 borderRadius: '50%',
                 overflow: 'hidden'
             }}
        >
        <Button
            size={SIZE.default}
            kind={KIND.secondary}
            shape={SHAPE.circle}
            onClick={toggleTheme}

            overrides={{
                BaseButton: {
                    style: ({ $theme }) => ({
                        transition: `all ${$theme.animation.timing500} ${$theme.animation.easeInOutQuinticCurve}`,
                        ':hover': {
                            backgroundColor: isLight
                                ? $theme.colors.contentPrimary
                                : $theme.colors.contentPrimary,
                            color: isLight
                                ? $theme.colors.backgroundPrimary
                                : $theme.colors.backgroundPrimary,
                        }
                    })
                },
            }}
        >
            <IconWrapper>
                {/* 1st child: Active icon */}
                <ActiveLayer $isHovered={isHovered}>
                    {isLight ? <Sun size={18} /> : <Moon size={18} />}
                </ActiveLayer>

                {/* 2nd child: Preview icon on hover */}
                <PreviewLayer $isHovered={isHovered}>
                    {isLight ? <Moon size={18} /> : <Sun size={18} />}
                </PreviewLayer>
            </IconWrapper>
        </Button>
        </div>
    );
}